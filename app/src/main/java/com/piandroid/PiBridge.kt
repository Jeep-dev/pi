package com.piandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

class PiBridge(
    context: Context,
    private val endpointPort: Int = DEFAULT_PORT,
    private val endpointToken: String? = null,
    private val runtimeOwnerSessionId: String = DEFAULT_ENDPOINT_KEY
) {
    private val context = context.applicationContext
    private val termux = "com.termux"
    private val service = "com.termux.app.RunCommandService"
    private val port = endpointPort
    private val expectedBridgeVersion = "2026-10-01.1"
    private val requiredBridgeCapabilities = setOf(
        "file-reference-v1",
        "durable-history-v1",
        "recovery-snapshot-v1",
        "persistent-widgets-v1",
        "multi-session-v1",
        "consistent-recovery-v1",
        "bounded-event-cache-v1",
        "hard-stop-v1",
        "conversation-owner-v1",
        "tool-history-metadata-v1",
        "native-settings-v1",
        "sse-stream-v1",
        "tool-args-lossless-v1",
        "cwd-shared-resume-v1",
        "closed-session-resume-v1"
    )
    private val authToken: String by lazy {
        endpointToken?.takeIf { it.length >= 32 } ?: PiBridge.endpointToken(context, runtimeOwnerSessionId)
    }
    private var nextId = 3000
    private val remoteBridgeDir = if (runtimeOwnerSessionId == DEFAULT_ENDPOINT_KEY) {
        "~/.pi/android"
    } else {
        "~/.pi/android/sessions/$runtimeOwnerSessionId"
    }
    private val remoteBridgeScript = "$remoteBridgeDir/bridge.mjs"
    private val remoteExtensionScript = "$remoteBridgeDir/pi-android-mobile.ts"
    private val remotePidFile = "$remoteBridgeDir/bridge.pid"
    private val remoteLogFile = "$remoteBridgeDir/bridge.log"
    private val lastSessionPreferenceKey = if (runtimeOwnerSessionId == DEFAULT_ENDPOINT_KEY) {
        "last_session_file"
    } else {
        "last_session_file_$runtimeOwnerSessionId"
    }

    fun applicationContext(): Context = context

    fun termuxAvailable(): Boolean = runCatching {
        context.packageManager.getPackageInfo(termux, 0)
    }.isSuccess

    /** Close this Android Session like a terminal tab: stop its runtime and keep all files/history. */
    suspend fun shutdownRuntime(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request("/shutdown", JSONObject().put("reason", "Android Session closed").toString(), 5_000).getOrThrow()
            delay(750)
            // Deliberately keep bridge files, session directories, JSONL history,
            // endpoint tokens, and runtime preferences. Closing a tab is not deletion.
        }
    }

    suspend fun installAndStartBridge(reason: String = "install"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!termuxAvailable()) return@withContext Result.failure(TermuxSetupException("请先安装 Termux"))
        runCatching {
            val shutdown = request("/shutdown", JSONObject().put("reason", "app relaunch: $reason").toString(), 2_000)
            // Nothing listened (a new Session, or a Bridge Android killed): no old
            // process to wait for. Otherwise give it a moment to exit by itself;
            // the launcher below still kills whatever is left on this port.
            if (!shutdown.exceptionOrNull().isConnectRefusedFailure()) delay(750)
            // Both scripts travel inside one argv string, which Linux caps at 128KB;
            // uncompressed they were already at 118KB. gzip keeps them far below.
            val bridge = gzipBase64("pi-android-bridge.mjs")
            val extension = gzipBase64("pi-android-mobile.ts")
            val d = "${'$'}"
            val logReason = reason.replace(Regex("[^A-Za-z0-9 .:_/-]"), " ").take(160)
            // Stop the process on the way out quickly: poll for its exit instead of sleeping.
            val waitGone = "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do kill -0 ${d}p 2>/dev/null || break; sleep 0.05; done; kill -9 ${d}p 2>/dev/null"
            // A Bridge left behind by a closed Session can still hold this port with
            // another token: it answers 401, ignores our /shutdown, and the new node
            // would die on EADDRINUSE. Kill any Bridge whose environment names this port.
            val freePort = "for f in ~/.pi/android/bridge.pid ~/.pi/android/sessions/*/bridge.pid; do " +
                "[ -f \"${d}f\" ] || continue; p=${d}(cat \"${d}f\" 2>/dev/null); [ -n \"${d}p\" ] || continue; " +
                "if tr '\\0' '\\n' < /proc/${d}p/environ 2>/dev/null | grep -qx 'PI_ANDROID_PORT=$port'; then " +
                "echo \"[${d}(date -u +%Y-%m-%dT%H:%M:%SZ)] launcher: stopping Bridge pid=${d}p on port $port (${d}f)\" >> $remoteLogFile; " +
                "kill ${d}p 2>/dev/null; $waitGone; fi; done"
            val command = """
                mkdir -p $remoteBridgeDir &&
                echo "[${d}(date -u +%Y-%m-%dT%H:%M:%SZ)] launcher start: $logReason" >> $remoteLogFile &&
                printf '%s' '$bridge' | base64 -d | gzip -dc > $remoteBridgeScript &&
                printf '%s' '$extension' | base64 -d | gzip -dc > $remoteExtensionScript &&
                chmod 700 $remoteBridgeScript &&
                { if [ -f $remotePidFile ]; then p="${d}(cat $remotePidFile)"; kill "${d}p" 2>/dev/null; $waitGone; fi; true; } &&
                { $freePort; true; } &&
                { (command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 &); true; } &&
                rm -f $remotePidFile &&
                export PI_ANDROID_TOKEN='$authToken' PI_ANDROID_PORT=$port PI_ANDROID_ENDPOINT_KEY='$runtimeOwnerSessionId' PI_ANDROID_PID_FILE=$remotePidFile &&
                exec /data/data/com.termux/files/usr/bin/node $remoteBridgeScript >> $remoteLogFile 2>&1
            """.trimIndent().replace("\n", " ")
            runTermux(command).getOrThrow()
        }
    }

    private fun gzipBase64(asset: String): String {
        val raw = context.assets.open(asset).use { it.readBytes() }
        val packed = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
        }.toByteArray()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    suspend fun waitForBridge(timeoutMillis: Long = 15_000): Result<Unit> {
        // Bounded by wall-clock time: a port that accepts but never answers used to
        // stretch "30s" of attempts to almost three minutes.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        var lastSeenVersion = ""
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).toInt().coerceIn(250, 1200)
            request("/health", null, remaining).onSuccess { raw ->
                val root = runCatching { JSONObject(raw) }.getOrNull()
                val version = root?.optString("bridgeVersion").orEmpty()
                val capabilities = root?.optJSONArray("capabilities") ?: JSONArray()
                val availableCapabilities = (0 until capabilities.length()).map { capabilities.optString(it) }.toSet()
                val missingCapabilities = requiredBridgeCapabilities - availableCapabilities
                val supportsRequired = missingCapabilities.isEmpty()
                lastSeenVersion = if (supportsRequired) version else "$version（缺少 ${missingCapabilities.joinToString()}）"
                if (version == expectedBridgeVersion && supportsRequired) {
                    return Result.success(Unit)
                }
            }
            delay(250)
        }
        val detail = if (lastSeenVersion.isBlank()) {
            "没有检测到新版 bridge"
        } else {
            "检测到旧 bridge：$lastSeenVersion，期望：$expectedBridgeVersion"
        }
        return Result.failure(IllegalStateException("Bridge 启动超时：$detail。打开 Termux 检查 $remoteLogFile"))
    }

    suspend fun start(cwd: String, launchCommand: String): Result<PiState> {
        val body = JSONObject().put("cwd", cwd).put("launchCommand", launchCommand).toString()
        return request("/start", body, 65_000).mapCatching {
            val root = JSONObject(it)
            val data = root.optJSONObject("state") ?: JSONObject()
            parseState(data)
        }
    }

    suspend fun health(timeoutMs: Int = 15_000): Result<PiHealth> = request("/health", null, timeoutMs).mapCatching {
        val root = JSONObject(it)
        PiHealth(
            piRunning = root.optBoolean("piRunning"),
            cwd = root.optString("cwd"),
            launchCommand = root.optString("launchCommand"),
            activeSessionFile = root.optString("activeSessionFile"),
            stderr = root.optString("lastStderr"),
            stdoutTail = root.optString("lastStdoutTail"),
            lastExit = root.optJSONObject("lastExit")?.let { exit ->
                "exit=${if (exit.isNull("code")) "?" else exit.optInt("code")} signal=${if (exit.isNull("signal")) "-" else exit.optString("signal")}"
            }.orEmpty(),
            bridgePid = root.optLong("bridgePid"),
            piPid = root.optLong("piPid"),
            port = root.optInt("port"),
            runtimeOwnerSessionId = root.optString("endpointKey"),
            stopInProgress = root.optBoolean("stopInProgress"),
            pendingPromptCount = root.optInt("pendingPromptCount", 0),
            compatible = root.optString("bridgeVersion") == expectedBridgeVersion &&
                (root.optJSONArray("capabilities") ?: JSONArray()).let { array ->
                    requiredBridgeCapabilities.all { required -> (0 until array.length()).any { array.optString(it) == required } }
                }
        )
    }

    suspend fun command(message: String): Result<Unit> {
        val body = JSONObject().put("message", message).toString()
        return request("/command", body, 10_000).map { Unit }
    }

    suspend fun prompt(
        message: String,
        streamingBehavior: String? = null,
        attachments: List<PiAttachment> = emptyList()
    ): Result<String> {
        val body = JSONObject().put("message", message).apply {
            if (!streamingBehavior.isNullOrBlank()) put("streamingBehavior", streamingBehavior)
            if (attachments.isNotEmpty()) put("attachments", JSONArray().apply {
                attachments.forEach { attachment ->
                    put(
                        JSONObject()
                            .put("name", attachment.name)
                            .put("path", attachment.path)
                            .put("mimeType", attachment.mimeType)
                            .put("byteCount", attachment.byteCount)
                    )
                }
            })
        }
        // Pi 0.99 answers with data.disposition: "started", "queued" or "handled" (an
        // extension or input handler consumed it and no run follows). Older Pi omits it.
        return request("/prompt", body.toString(), SLOW_RPC_TIMEOUT_MS).map { raw ->
            runCatching { JSONObject(raw).optJSONObject("data")?.optString("disposition").orEmpty() }.getOrDefault("")
        }
    }

    suspend fun referenceAttachment(path: String, name: String, mimeType: String, byteCount: Long): Result<PiAttachment> {
        return request("/reference?path=${encode(path)}", null, 8_000).mapCatching { raw ->
            val data = JSONObject(raw)
            PiAttachment(name, mimeType, data.optString("path"), data.optLong("byteCount", byteCount))
        }
    }

    suspend fun uploadAttachment(uri: Uri, name: String, mimeType: String, byteCount: Long): Result<PiAttachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("http://127.0.0.1:$port/upload?name=${encode(name)}")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 0
                    doOutput = true
                    useCaches = false
                    setChunkedStreamingMode(256 * 1024)
                    setRequestProperty("Authorization", "Bearer $authToken")
                    setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })
                }
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取所选文件" }
                    connection.outputStream.use { output -> input.copyTo(output, 256 * 1024) }
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                        throw IllegalStateException(message.ifBlank { "附件导入失败：HTTP $code" })
                    }
                    val stored = JSONObject(text)
                    PiAttachment(name, mimeType, stored.optString("path"), stored.optLong("byteCount", byteCount))
                } finally {
                    connection.disconnect()
                }
            }
        }

    /** Stop the active agent, clear Pi's queues, and fence new work in the bridge. */
    suspend fun stop(): Result<Unit> = request("/stop", "{}", 70_000).map { Unit }

    // Kept as a source-compatible alias for older callers.
    suspend fun abort(): Result<Unit> = stop()

    suspend fun clearQueue(): Result<PiQueue> = request("/clear-queue", "{}", 8_000).mapCatching { raw ->
        val data = JSONObject(raw).optJSONObject("data") ?: JSONObject()
        PiQueue(
            steering = data.optJSONArray("steering")?.let { array -> List(array.length()) { index -> array.optString(index) } }.orEmpty(),
            followUp = data.optJSONArray("followUp")?.let { array -> List(array.length()) { index -> array.optString(index) } }.orEmpty()
        )
    }

    suspend fun newSession(): Result<Unit> = request("/new-session", "{}").map { Unit }
    suspend fun cloneSession(): Result<Unit> = request("/clone", "{}").map { Unit }

    suspend fun compact(instructions: String = ""): Result<Unit> {
        val body = JSONObject().apply { if (instructions.isNotBlank()) put("instructions", instructions) }
        return request("/compact", body.toString(), 4 * 60 * 60 * 1000).map { Unit }
    }

    suspend fun state(timeoutMs: Int = 15_000): Result<PiState> = rpcData("/state", timeoutMs).mapCatching(::parseState)

    suspend fun stats(): Result<PiStats> = rpcData("/stats", 35_000).mapCatching { data ->
        val tokens = data.optJSONObject("tokens") ?: JSONObject()
        val usage = data.optJSONObject("contextUsage")
        PiStats(
            sessionFile = data.optString("sessionFile"),
            piConversationId = data.optString("sessionId"),
            totalMessages = data.optInt("totalMessages"),
            inputTokens = tokens.optLong("input"),
            outputTokens = tokens.optLong("output"),
            cacheRead = tokens.optLong("cacheRead"),
            cacheWrite = tokens.optLong("cacheWrite"),
            latestCacheHitRate = if (data.isNull("latestCacheHitRate")) -1.0 else data.optDouble("latestCacheHitRate", -1.0),
            cost = data.optDouble("cost", 0.0),
            contextTokens = usage?.optLong("tokens", -1L) ?: -1L,
            contextWindow = usage?.optLong("contextWindow", -1L) ?: -1L,
            contextPercent = usage?.optDouble("percent", -1.0) ?: -1.0
        )
    }

    suspend fun models(): Result<List<PiModel>> = rpcData("/models").mapCatching { data ->
        val array = data.optJSONArray("models") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    PiModel(
                        provider = item.optString("provider"),
                        id = item.optString("id"),
                        name = item.optString("name", item.optString("id")),
                        reasoning = item.optBoolean("reasoning"),
                        contextWindow = item.optLong("contextWindow")
                    )
                )
            }
        }
    }

    suspend fun setModel(model: PiModel): Result<Unit> {
        val body = JSONObject().put("provider", model.provider).put("modelId", model.id).toString()
        return request("/model", body, SLOW_RPC_TIMEOUT_MS).map { Unit }
    }

    private fun runtimePreferences() = context.getSharedPreferences("pi_runtime", Context.MODE_PRIVATE)

    fun recoveryLaunchCommand(baseCommand: String, activeSessionFile: String? = null): String {
        val sessionFile = activeSessionFile
            ?.takeIf { it.isNotBlank() }
            ?: runtimePreferences().getString(lastSessionPreferenceKey, "").orEmpty()
        return pinPiLaunchToSession(baseCommand, sessionFile)
    }

    /** Build a clean command for a new cwd without inheriting any old session selector. */
    fun freshLaunchCommand(
        baseCommand: String,
        sessionId: String = "",
        sessionDirectory: String = ""
    ): String {
        val fresh = launchPiWithoutSession(baseCommand)
        return if (sessionId.isNotBlank() && sessionDirectory.isNotBlank()) {
            ensurePiSessionIdentity(fresh, sessionId, sessionDirectory)
        } else {
            fresh
        }
    }

    fun defaultModelKey(): String = context.getSharedPreferences("model_defaults", Context.MODE_PRIVATE)
        .getString("default_model", "").orEmpty()

    fun saveDefaultModel(model: PiModel) {
        context.getSharedPreferences("model_defaults", Context.MODE_PRIVATE)
            .edit().putString("default_model", "${model.provider}/${model.id}").apply()
    }

    suspend fun applyDefaultModel(models: List<PiModel>): Result<PiModel?> {
        val key = defaultModelKey()
        if (key.isBlank()) return Result.success(null)
        val model = models.firstOrNull { "${it.provider}/${it.id}" == key }
            ?: return Result.failure(IllegalStateException("默认模型已不可用：$key"))
        return setModel(model).map { model }
    }

    suspend fun setThinking(level: String): Result<Unit> {
        return request("/thinking", JSONObject().put("level", level).toString(), SLOW_RPC_TIMEOUT_MS).map { Unit }
    }

    suspend fun setSteeringMode(mode: String): Result<Unit> =
        request("/steering-mode", JSONObject().put("mode", mode).toString()).map { Unit }

    suspend fun setFollowUpMode(mode: String): Result<Unit> =
        request("/follow-up-mode", JSONObject().put("mode", mode).toString()).map { Unit }

    suspend fun setAutoRetry(enabled: Boolean): Result<Unit> =
        request("/auto-retry", JSONObject().put("enabled", enabled).toString()).map { Unit }

    /** Thinking levels the current model actually supports (native /thinking only offers these). */
    suspend fun thinkingLevels(): Result<List<String>> = rpcData("/thinking-levels").mapCatching { data ->
        val array = data.optJSONArray("levels") ?: JSONArray()
        List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }

    suspend fun changelog(limit: Int = 3): Result<Pair<String, String>> = request("/changelog?limit=$limit", null).mapCatching { raw ->
        val json = JSONObject(raw)
        json.optString("version") to json.optString("text")
    }

    suspend fun setAutoCompaction(enabled: Boolean): Result<Unit> {
        return request("/auto-compaction", JSONObject().put("enabled", enabled).toString()).map { Unit }
    }

    suspend fun lastAssistantText(): Result<String> = rpcData("/last-assistant").mapCatching { data ->
        data.optString("text")
    }

    suspend fun exportHtml(outputPath: String = ""): Result<String> {
        val body = JSONObject().apply { if (outputPath.isNotBlank()) put("outputPath", outputPath) }
        return request("/export-html", body.toString(), 130_000).mapCatching { raw ->
            JSONObject(raw).optJSONObject("data")?.optString("path").orEmpty()
        }
    }

    suspend fun commands(): Result<List<PiCommand>> = rpcData("/commands").mapCatching { data ->
        val array = data.optJSONArray("commands") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    PiCommand(
                        name = item.optString("name"),
                        description = item.optString("description"),
                        source = item.optString("source")
                    )
                )
            }
        }
    }

    suspend fun bash(command: String, excludeFromContext: Boolean = false): Result<PiBashResult> {
        val body = JSONObject()
            .put("command", command)
            .put("excludeFromContext", excludeFromContext)
            .toString()
        return request("/bash", body, 4 * 60 * 60 * 1000).mapCatching { raw ->
            val data = JSONObject(raw).optJSONObject("data") ?: JSONObject()
            PiBashResult(
                output = data.optString("output"),
                exitCode = data.optInt("exitCode", -1),
                cancelled = data.optBoolean("cancelled"),
                truncated = data.optBoolean("truncated")
            )
        }
    }

    suspend fun abortBash(): Result<Unit> = request("/abort-bash", "{}").map { Unit }

    suspend fun extensionUiResponse(
        id: String,
        value: String? = null,
        confirmed: Boolean? = null,
        cancelled: Boolean = false
    ): Result<Unit> {
        val body = JSONObject().put("id", id).apply {
            if (value != null) put("value", value)
            if (confirmed != null) put("confirmed", confirmed)
            if (cancelled) put("cancelled", true)
        }
        return request("/extension-ui", body.toString()).map { Unit }
    }

    /**
     * Hold one text/event-stream open and deliver every batch in order. Returns
     * normally when the server ends the stream; throws on socket failure. The
     * Bridge sends a heartbeat every 10s, so a read timeout means a dead link,
     * never a quiet agent. [onOpen] fires once the first frame arrives.
     */
    suspend fun stream(
        after: Long,
        onOpen: () -> Unit,
        keepOpen: () -> Boolean = { true },
        onBatch: suspend (PiEventBatch) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("http://127.0.0.1:$port/stream?after=$after").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                // Heartbeats come every 10s, so 25s of silence means a stalled Bridge.
                readTimeout = 25_000
                useCaches = false
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Accept", "text/event-stream")
            }
            val job = coroutineContext[Job]
            // Blocking reads ignore cancellation; closing the socket unblocks them.
            val cancelHandle = job?.invokeOnCompletion { connection.disconnect() }
            try {
                val code = connection.responseCode
                check(code == 200) { "stream HTTP $code" }
                val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
                val data = StringBuilder()
                var opened = false
                while (true) {
                    job?.ensureActive()
                    val line = reader.readLine() ?: break
                    if (!keepOpen()) break
                    when {
                        line.isEmpty() -> if (data.isNotEmpty()) {
                            val batch = parseEventBatch(JSONObject(data.toString()), after)
                            data.setLength(0)
                            if (!opened) { opened = true; onOpen() }
                            onBatch(batch)
                        }
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                        else -> Unit // ": ping" heartbeat or unknown field
                    }
                }
            } finally {
                cancelHandle?.dispose()
                connection.disconnect()
            }
        }
    }

    private fun parseEventBatch(root: JSONObject, after: Long): PiEventBatch {
        val array = root.optJSONArray("events") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val value = item.optJSONObject("value") ?: JSONObject().put("type", "raw").put("line", item.optString("value"))
                add(parseEvent(item.optLong("seq"), value))
            }
        }
        return PiEventBatch(parsed, root.optLong("latest", after), root.optBoolean("gap"))
    }

    suspend fun events(after: Long): Result<PiEventBatch> = request("/events?after=$after&wait=20000", null, 35_000).mapCatching { raw ->
        val root = JSONObject(raw)
        val array = root.optJSONArray("events") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val value = item.optJSONObject("value") ?: JSONObject().put("type", "raw").put("line", item.optString("value"))
                add(parseEvent(item.optLong("seq"), value))
            }
        }
        PiEventBatch(parsed, root.optLong("latest", after), root.optBoolean("gap"))
    }

    suspend fun files(path: String = ""): Result<List<PiFile>> = request("/files?path=${encode(path)}", null).mapCatching { raw ->
        val array = JSONObject(raw).optJSONArray("entries") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(PiFile(item.optString("name"), item.optString("type"), item.optString("path")))
            }
        }
    }

    suspend fun file(path: String): Result<PiFileContent> = request("/file?path=${encode(path)}", null).mapCatching {
        val root = JSONObject(it)
        PiFileContent(root.optString("content"), root.optBoolean("truncated"))
    }

    suspend fun writeFile(path: String, content: String): Result<Unit> {
        val body = JSONObject().put("path", path).put("content", content).toString()
        return request("/file", body, 15_000).map { Unit }
    }

    suspend fun diff(): Result<String> = request("/diff", null).mapCatching {
        val root = JSONObject(it)
        val diff = root.optString("diff")
        if (diff.isBlank() && root.optString("error").isNotBlank()) root.optString("error") else diff
    }

    private suspend fun rpcData(path: String, timeoutMs: Int = 15_000): Result<JSONObject> = request(path, null, timeoutMs).mapCatching { raw ->
        val root = JSONObject(raw)
        root.optJSONObject("data") ?: JSONObject()
    }

    internal fun parseState(data: JSONObject): PiState {
        val model = data.optJSONObject("model")
        val state = PiState(
            provider = model?.optString("provider").orEmpty(),
            modelId = model?.optString("id").orEmpty(),
            modelName = model?.optString("name").orEmpty(),
            thinkingLevel = data.optString("thinkingLevel"),
            streaming = data.optBoolean("isStreaming"),
            compacting = data.optBoolean("isCompacting"),
            sessionFile = data.optString("sessionFile"),
            piConversationId = data.optString("sessionId"),
            sessionName = data.optString("sessionName"),
            messageCount = data.optInt("messageCount"),
            autoCompactionEnabled = data.optBoolean("autoCompactionEnabled", true),
            steeringMode = data.optString("steeringMode").ifBlank { "one-at-a-time" },
            followUpMode = data.optString("followUpMode").ifBlank { "one-at-a-time" }
        )
        if (state.sessionFile.isNotBlank()) {
            val preferences = runtimePreferences()
            if (preferences.getString(lastSessionPreferenceKey, "") != state.sessionFile) {
                // Recovery correctness is more important than an asynchronous
                // write here: a process death immediately after /resume must not
                // fall back to the previously active conversation.
                preferences.edit().putString(lastSessionPreferenceKey, state.sessionFile).commit()
            }
        }
        return state
    }

    private fun messageText(message: JSONObject): String {
        val content = message.opt("content")
        if (content is String) return content
        if (content !is JSONArray) return ""
        return buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
    }

    private fun toolArgsText(toolName: String, args: JSONObject?): String =
        formatToolArgs(toolName, args)

    private fun compactToolNumber(value: Long): String = when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fm", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    private fun subagentMeta(details: JSONObject?): String {
        if (details?.optString("kind") != "pi-subagent-progress") return ""
        val runs = details.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                if (isNotEmpty()) append('\n')
                val status = run.optString("status")
                val icon = when (status) {
                    "succeeded" -> "✓"
                    "failed", "error" -> "✗"
                    "running" -> "●"
                    else -> "○"
                }
                append(icon).append(' ').append(run.optString("agent", "subagent"))
                run.optString("model").substringAfter('/').takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                val usage = run.optJSONObject("usage")
                val turns = usage?.optInt("turns", 0) ?: 0
                val ctx = usage?.optLong("ctxTokens", 0L) ?: 0L
                val cost = usage?.optDouble("cost", 0.0) ?: 0.0
                if (turns > 0) append(" · ").append(turns).append(if (turns == 1) " turn" else " turns")
                if (ctx > 0) append(" · ").append(compactToolNumber(ctx)).append(" ctx")
                if (cost > 0.0) append(" · $").append(String.format(java.util.Locale.US, "%.4f", cost))
            }
        }
    }

    internal fun parseEvent(seq: Long, value: JSONObject): PiEvent {
        val type = value.optString("type")
        return when (type) {
            "message_update" -> {
                val delta = value.optJSONObject("assistantMessageEvent") ?: JSONObject()
                val subtype = delta.optString("type")
                val text = when (subtype) {
                    "text_delta", "thinking_delta", "toolcall_delta" -> delta.optString("delta")
                    "toolcall_start" -> delta.optString("toolName")
                    "toolcall_end" -> delta.optJSONObject("toolCall")?.optString("name").orEmpty()
                    else -> ""
                }
                PiEvent(
                    seq, type, subtype, text,
                    toolCallId = delta.optString("id"),
                    contentIndex = delta.optInt("contentIndex", -1),
                    toolName = delta.optString("toolName", delta.optJSONObject("toolCall")?.optString("name").orEmpty())
                )
            }
            "prompt_submission_end" -> PiEvent(seq, type, value.optString("disposition"), "", isError = !value.optBoolean("success", true))
            "message_end" -> {
                val message = value.optJSONObject("message") ?: JSONObject()
                PiEvent(
                    seq, type,
                    if (message.optString("role") == "custom" && !message.optBoolean("display", true)) "hidden" else message.optString("role"),
                    messageText(message),
                    stopReason = message.optString("stopReason"),
                    errorMessage = message.optString("errorMessage")
                )
            }
            "tool_execution_start" -> {
                val args = value.optJSONObject("args")
                val toolName = value.optString("toolName")
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = "",
                    toolCallId = value.optString("toolCallId"),
                    argsText = toolArgsText(toolName, args),
                    toolName = toolName,
                    parentToolCallId = value.optString("parentToolCallId")
                )
            }
            "tool_execution_update" -> {
                val content = value.optJSONObject("partialResult")?.optJSONArray("content") ?: JSONArray()
                val text = buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") {
                            if (isNotEmpty()) append('\n')
                            append(part.optString("text"))
                        }
                    }
                }
                val toolName = value.optString("toolName")
                val meta = subagentMeta(value.optJSONObject("partialResult")?.optJSONObject("details"))
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = if (meta.isNotBlank() && text.trim() == "subagent running") "" else text,
                    toolCallId = value.optString("toolCallId"),
                    argsText = toolArgsText(toolName, value.optJSONObject("args")),
                    toolName = toolName,
                    metaText = meta,
                    parentToolCallId = value.optString("parentToolCallId")
                )
            }
            "queue_update" -> {
                val steering = value.optJSONArray("steering")?.let { array ->
                    List(array.length()) { index -> array.optString(index) }
                }.orEmpty()
                val followUp = value.optJSONArray("followUp")?.let { array ->
                    List(array.length()) { index -> array.optString(index) }
                }.orEmpty()
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = "",
                    steeringCount = steering.size,
                    followUpCount = followUp.size,
                    steeringQueue = steering,
                    followUpQueue = followUp
                )
            }
            "tool_execution_end" -> {
                val result = value.optJSONObject("result")
                val content = result?.optJSONArray("content") ?: JSONArray()
                val output = buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        val text = part.optString("text")
                        if (text.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(text)
                        }
                    }
                }
                val name = value.optString("toolName")
                val details = result?.optJSONObject("details")
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = toolResultText(
                        toolName = name,
                        output = output,
                        isError = value.optBoolean("isError"),
                        diff = details?.optString("diff").orEmpty()
                    ),
                    toolCallId = value.optString("toolCallId"),
                    toolName = name,
                    metaText = subagentMeta(details),
                    isError = value.optBoolean("isError"),
                    parentToolCallId = value.optString("parentToolCallId")
                )
            }
            "compaction_start", "compaction_end" -> {
                val outcome = when {
                    type == "compaction_start" -> ""
                    value.optBoolean("aborted") -> "aborted"
                    value.optJSONObject("result") != null -> "success"
                    else -> "error"
                }
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = value.optString("reason"),
                    text = value.optString("errorMessage").ifBlank {
                        if (type == "compaction_start") "正在压缩上下文" else "上下文压缩完成"
                    },
                    stopReason = outcome
                )
            }
            "stderr" -> PiEvent(seq, type, "", value.optString("text"))
            "process_exit" -> PiEvent(seq, type, "", "Pi 进程退出：${value.optString("code", value.optString("signal"))}\n${value.optString("stderr")}".trim())
            "extension_error" -> PiEvent(seq, type, "", value.optString("error", value.toString()))
            "extension_ui_request" -> {
                val method = value.optString("method")
                val optionsArray = if (method == "setWidget") {
                    value.optJSONArray("widgetLines") ?: JSONArray()
                } else {
                    value.optJSONArray("options") ?: JSONArray()
                }
                val options = buildList { for (i in 0 until optionsArray.length()) add(optionsArray.optString(i)) }
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = method,
                    text = value.optString("message", value.optString("text", value.optString("statusText"))),
                    uiRequest = PiUiRequest(
                        id = value.optString("id"),
                        method = method,
                        title = if (method == "setWidget") value.optString("widgetKey") else value.optString("title"),
                        message = value.optString("message", value.optString("text")),
                        options = options,
                        placeholder = value.optString("placeholder"),
                        prefill = value.optString("prefill"),
                        notifyType = value.optString("notifyType"),
                        statusText = value.optString("statusText")
                    )
                )
            }
            else -> PiEvent(seq, type, "", when (type) {
                "agent_start" -> "Pi 开始处理"
                "agent_end", "agent_settled" -> "本轮完成"
                "compaction_start" -> "正在压缩上下文"
                "compaction_end" -> "上下文压缩完成"
                "auto_retry_start" -> "正在自动重试"
                "auto_retry_end" -> "自动重试结束"
                "raw" -> value.optString("line")
                else -> ""
            })
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * Run a no-op in Termux. Starting Termux's command service thaws a Termux the
     * system froze in the background, and with it this Bridge and its Pi child,
     * without restarting either. It also asks Termux to hold its wake lock so the
     * Bridge keeps running while the screen is off.
     */
    suspend fun wakeTermux(force: Boolean = false, minIntervalMs: Long = WAKE_INTERVAL_MS): Result<Unit> {
        // The delivered intent is what thaws Termux, so run the cheapest command
        // there is: no login shell, no `am`. One wake per interval for the whole
        // app; every Session and the keep-alive used to send their own.
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(wakeGate) {
            if (!force && lastWakeAtMs != 0L && now - lastWakeAtMs < minIntervalMs) return Result.success(Unit)
            lastWakeAtMs = now
        }
        return runTermuxExecutable("/data/data/com.termux/files/usr/bin/true", emptyArray())
    }

    private suspend fun runTermux(command: String): Result<Unit> =
        runTermuxExecutable("/data/data/com.termux/files/usr/bin/bash", arrayOf("-lc", command))

    private suspend fun runTermuxExecutable(path: String, arguments: Array<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").setClassName(termux, service)
                .putExtra("com.termux.RUN_COMMAND_PATH", path)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            val started = try {
                context.startService(intent)
            } catch (backgroundStart: IllegalStateException) {
                // Background start limits: Termux's RunCommandService goes foreground itself.
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else throw backgroundStart
            }
            if (started == null) {
                android.util.Log.w("PiBridge", "RUN_COMMAND not delivered path=$path")
                return@withContext Result.failure(IllegalStateException("Termux 拒绝了启动命令（系统拦截或 Termux 未安装）"))
            }
            Result.success(Unit)
        } catch (_: SecurityException) {
            Result.failure(TermuxSetupException("请给 Pi Android 开启 Termux 的 RUN_COMMAND 权限，并确认 ~/.termux/termux.properties 中 allow-external-apps=true"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun request(path: String, body: String?, timeoutMs: Int = 15_000): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = timeoutMs.coerceAtMost(10_000)
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Authorization", "Bearer $authToken")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            try {
                val code = try {
                    connection.responseCode
                } catch (timeout: java.net.SocketTimeoutException) {
                    val hint = if (path == "/prompt" || path == "/model" || path == "/stats") {
                        "可能是 Termux 被系统冻结，或模型 provider 网络不通"
                    } else {
                        "Termux 可能被系统冻结，正在唤醒"
                    }
                    throw IllegalStateException("Pi ${timeoutMs / 1000} 秒内没有响应（$path）；$hint", timeout)
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw BridgeHttpException(code, message.ifBlank { "HTTP $code: $text" })
                }
                text
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val DEFAULT_PORT = 17649
        /** Must stay above the Bridge's SLOW_RPC_TIMEOUT_MS so the Bridge reports which RPC stalled. */
        const val SLOW_RPC_TIMEOUT_MS = 40_000
        const val DEFAULT_ENDPOINT_KEY = "default"
        private const val WAKE_INTERVAL_MS = 10_000L
        private val wakeGate = Any()
        private var lastWakeAtMs = 0L

        internal fun endpointToken(context: Context, key: String): String {
            val preferences = context.applicationContext.getSharedPreferences("bridge_security", Context.MODE_PRIVATE)
            val preferenceKey = if (key == DEFAULT_ENDPOINT_KEY) "auth_token" else "auth_token_$key"
            preferences.getString(preferenceKey, null)?.takeIf { it.length >= 32 }?.let { return it }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            check(preferences.edit().putString(preferenceKey, token).commit()) { "无法保存 Bridge 认证信息" }
            return token
        }

        internal fun forgetEndpointToken(context: Context, key: String) {
            val preferenceKey = if (key == DEFAULT_ENDPOINT_KEY) "auth_token" else "auth_token_$key"
            context.applicationContext.getSharedPreferences("bridge_security", Context.MODE_PRIVATE)
                .edit().remove(preferenceKey).commit()
        }
    }
}

/** The Bridge answered with an HTTP error: its process is alive and responsive. */
class BridgeHttpException(val code: Int, message: String) : IllegalStateException(message)

/** True when [this] or a cause is a 401: another Session's Bridge holds this port. */
internal fun Throwable?.isForeignBridgeFailure(): Boolean {
    var current = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is BridgeHttpException && current.code == 401) return true
        current = current.cause
    }
    return false
}

/** A local setup problem that retrying cannot fix; the user must act first. */
class TermuxSetupException(message: String) : IllegalStateException(message)

/** True when [this] or a cause is a socket timeout: something holds the port but did not answer. */
internal fun Throwable?.isSocketTimeoutFailure(): Boolean {
    var current = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is java.net.SocketTimeoutException) return true
        current = current.cause
    }
    return false
}

/** True when [this] or a cause is a refused connection: nothing listens on the port. */
internal fun Throwable?.isConnectRefusedFailure(): Boolean {
    var current = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        // Android's ConnectException can omit the errno; its ErrnoException cause carries it.
        val message = current.message.orEmpty()
        if (Regex("(?i)\\bECONNREFUSED\\b").containsMatchIn(message)) return true
        if (current is java.net.ConnectException && message.contains("connection refused", ignoreCase = true)) return true
        current = current.cause
    }
    return false
}

data class PiHealth(
    val piRunning: Boolean,
    val cwd: String,
    val launchCommand: String,
    val activeSessionFile: String,
    val stderr: String,
    val stdoutTail: String = "",
    val lastExit: String = "",
    val bridgePid: Long = 0L,
    val piPid: Long = 0L,
    val port: Int = 0,
    val runtimeOwnerSessionId: String = "",
    val stopInProgress: Boolean = false,
    val pendingPromptCount: Int = 0,
    /** Bridge build matches this APK; an incompatible bridge must be replaced (only while Pi is idle). */
    val compatible: Boolean = true
)

data class PiQueue(
    val steering: List<String>,
    val followUp: List<String>
)
data class PiState(
    val provider: String,
    val modelId: String,
    val modelName: String,
    val thinkingLevel: String,
    val streaming: Boolean,
    val compacting: Boolean,
    val sessionFile: String,
    val piConversationId: String,
    val sessionName: String,
    val messageCount: Int,
    val autoCompactionEnabled: Boolean,
    /** Pi queue delivery: "one-at-a-time" or "all" (native /settings). */
    val steeringMode: String = "one-at-a-time",
    val followUpMode: String = "one-at-a-time"
)
data class PiStats(
    val sessionFile: String,
    val piConversationId: String,
    val totalMessages: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val latestCacheHitRate: Double,
    val cost: Double,
    val contextTokens: Long,
    val contextWindow: Long,
    val contextPercent: Double
)
data class PiModel(val provider: String, val id: String, val name: String, val reasoning: Boolean, val contextWindow: Long)
data class PiAttachment(val name: String, val mimeType: String, val path: String, val byteCount: Long)
data class PiCommand(val name: String, val description: String, val source: String)
data class PiBashResult(val output: String, val exitCode: Int, val cancelled: Boolean, val truncated: Boolean)
data class PiUiRequest(
    val id: String,
    val method: String,
    val title: String,
    val message: String,
    val options: List<String>,
    val placeholder: String,
    val prefill: String,
    val notifyType: String,
    val statusText: String
)
data class PiEvent(
    val seq: Long,
    val type: String,
    val subtype: String,
    val text: String,
    val uiRequest: PiUiRequest? = null,
    val toolCallId: String = "",
    val contentIndex: Int = -1,
    val stopReason: String = "",
    val errorMessage: String = "",
    val steeringCount: Int = 0,
    val followUpCount: Int = 0,
    val steeringQueue: List<String> = emptyList(),
    val followUpQueue: List<String> = emptyList(),
    val argsText: String = "",
    val toolName: String = "",
    val metaText: String = "",
    val isError: Boolean = false,
    /** Set on tool calls made from inside another tool (codemode scripts, MCP via codemode). */
    val parentToolCallId: String = ""
)
data class PiEventBatch(val events: List<PiEvent>, val latest: Long, val gap: Boolean)
data class PiFile(val name: String, val type: String, val path: String)
data class PiFileContent(val content: String, val truncated: Boolean)
