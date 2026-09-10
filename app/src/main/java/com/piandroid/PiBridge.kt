package com.piandroid

import android.content.Context
import android.content.Intent
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

class PiBridge(context: Context) {
    private val context = context.applicationContext
    private val termux = "com.termux"
    private val service = "com.termux.app.RunCommandService"
    private val port = 17649
    private val expectedBridgeVersion = "2026-09-10.5"
    private val authToken: String by lazy(::loadOrCreateAuthToken)
    private var nextId = 3000

    fun applicationContext(): Context = context

    fun termuxAvailable(): Boolean = runCatching {
        context.packageManager.getPackageInfo(termux, 0)
    }.isSuccess

    suspend fun installAndStartBridge(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!termuxAvailable()) return@withContext Result.failure(IllegalStateException("请先安装 Termux"))
        runCatching {
            request("/shutdown", "{}", 2_000)
            delay(750)
            val bridge = context.assets.open("pi-android-bridge.mjs").use {
                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
            }
            val extension = context.assets.open("pi-android-mobile.ts").use {
                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
            }
            val command = """
                mkdir -p ~/.pi/android &&
                printf '%s' '$bridge' | base64 -d > ~/.pi/android/bridge.mjs &&
                printf '%s' '$extension' | base64 -d > ~/.pi/android/pi-android-mobile.ts &&
                chmod 700 ~/.pi/android/bridge.mjs &&
                if [ -f ~/.pi/android/bridge.pid ]; then old_pid="${'$'}(cat ~/.pi/android/bridge.pid)"; kill "${'$'}old_pid" 2>/dev/null || true; sleep 0.7; kill -9 "${'$'}old_pid" 2>/dev/null || true; fi &&
                rm -f ~/.pi/android/bridge.pid &&
                export PI_ANDROID_TOKEN='$authToken' PI_ANDROID_PORT=$port &&
                exec /data/data/com.termux/files/usr/bin/node ~/.pi/android/bridge.mjs >> ~/.pi/android/bridge.log 2>&1
            """.trimIndent().replace("\n", " ")
            runTermux(command).getOrThrow()
        }
    }

    suspend fun waitForBridge(timeoutMillis: Long = 15_000): Result<Unit> {
        val attempts = (timeoutMillis / 250).toInt().coerceAtLeast(1)
        var lastSeenVersion = ""
        repeat(attempts) {
            request("/health", null, 1200).onSuccess { raw ->
                val root = runCatching { JSONObject(raw) }.getOrNull()
                val version = root?.optString("bridgeVersion").orEmpty()
                lastSeenVersion = version
                if (version == expectedBridgeVersion) {
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
        return Result.failure(IllegalStateException("Bridge 启动超时：$detail。打开 Termux 检查 ~/.pi/android/bridge.log"))
    }

    suspend fun attachToRunningBridge(): Result<PiState> = runCatching {
        waitForBridge(1_500).getOrThrow()
        val health = health().getOrThrow()
        check(health.piRunning) { "Pi is not running" }
        state().getOrThrow()
    }

    suspend fun start(cwd: String, launchCommand: String): Result<PiState> {
        val body = JSONObject().put("cwd", cwd).put("launchCommand", launchCommand).toString()
        return request("/start", body, 15_000).mapCatching {
            val root = JSONObject(it)
            val data = root.optJSONObject("state") ?: JSONObject()
            parseState(data)
        }
    }

    suspend fun health(): Result<PiHealth> = request("/health", null).mapCatching {
        val root = JSONObject(it)
        PiHealth(
            piRunning = root.optBoolean("piRunning"),
            cwd = root.optString("cwd"),
            launchCommand = root.optString("launchCommand"),
            stderr = root.optString("lastStderr")
        )
    }

    suspend fun prompt(
        message: String,
        streamingBehavior: String? = null,
        attachments: List<PiAttachment> = emptyList()
    ): Result<Unit> {
        val body = JSONObject().put("message", message).apply {
            if (!streamingBehavior.isNullOrBlank()) put("streamingBehavior", streamingBehavior)
            if (attachments.isNotEmpty()) put("attachments", JSONArray().apply {
                attachments.forEach { attachment ->
                    put(
                        JSONObject()
                            .put("name", attachment.name)
                            .put("data", attachment.data)
                            .put("mimeType", attachment.mimeType)
                    )
                }
            })
        }
        return request("/prompt", body.toString()).map { Unit }
    }

    suspend fun abort(): Result<Unit> = request("/abort", "{}").map { Unit }
    suspend fun newSession(): Result<Unit> = request("/new-session", "{}").map { Unit }
    suspend fun cloneSession(): Result<Unit> = request("/clone", "{}").map { Unit }

    suspend fun compact(instructions: String = ""): Result<Unit> {
        val body = JSONObject().apply { if (instructions.isNotBlank()) put("instructions", instructions) }
        return request("/compact", body.toString(), 4 * 60 * 60 * 1000).map { Unit }
    }

    suspend fun state(): Result<PiState> = rpcData("/state").mapCatching(::parseState)

    suspend fun stats(): Result<PiStats> = rpcData("/stats").mapCatching { data ->
        val tokens = data.optJSONObject("tokens") ?: JSONObject()
        val usage = data.optJSONObject("contextUsage")
        PiStats(
            sessionFile = data.optString("sessionFile"),
            sessionId = data.optString("sessionId"),
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
        return request("/model", body).map { Unit }
    }

    private fun runtimePreferences() = context.getSharedPreferences("pi_runtime", Context.MODE_PRIVATE)

    fun recoveryLaunchCommand(baseCommand: String): String {
        if (Regex("(^|\\s)--session(?:=|\\s)").containsMatchIn(baseCommand)) return baseCommand
        val sessionFile = runtimePreferences().getString("last_session_file", "").orEmpty()
        if (sessionFile.isBlank()) return baseCommand
        val quoted = "'${sessionFile.replace("'", "'\\''")}'"
        return "$baseCommand --session $quoted"
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
        return request("/thinking", JSONObject().put("level", level).toString()).map { Unit }
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

    suspend fun events(after: Long): Result<PiEventBatch> = request("/events?after=$after&wait=20000", null, 25_000).mapCatching { raw ->
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

    suspend fun file(path: String): Result<String> = request("/file?path=${encode(path)}", null).mapCatching {
        JSONObject(it).optString("content")
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

    private suspend fun rpcData(path: String): Result<JSONObject> = request(path, null).mapCatching { raw ->
        val root = JSONObject(raw)
        root.optJSONObject("data") ?: JSONObject()
    }

    private fun parseState(data: JSONObject): PiState {
        val model = data.optJSONObject("model")
        val state = PiState(
            provider = model?.optString("provider").orEmpty(),
            modelId = model?.optString("id").orEmpty(),
            modelName = model?.optString("name").orEmpty(),
            thinkingLevel = data.optString("thinkingLevel"),
            streaming = data.optBoolean("isStreaming"),
            compacting = data.optBoolean("isCompacting"),
            sessionFile = data.optString("sessionFile"),
            sessionId = data.optString("sessionId"),
            sessionName = data.optString("sessionName"),
            messageCount = data.optInt("messageCount"),
            autoCompactionEnabled = data.optBoolean("autoCompactionEnabled", true)
        )
        if (state.sessionFile.isNotBlank()) {
            runtimePreferences().edit().putString("last_session_file", state.sessionFile).apply()
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

    private fun toolArgsText(toolName: String, args: JSONObject?): String {
        if (args == null || args.length() == 0) return ""
        return when (toolName) {
            "write" -> {
                val content = args.optString("content")
                buildString {
                    append("目标：${args.optString("path")}\n")
                    append("内容：${content.length} 字符")
                    if (content.isNotBlank()) {
                        val preview = content.takeLast(1_600).lineSequence().toList().takeLast(18).joinToString("\n")
                        append("\n\n写入预览${if (content.length > preview.length) "（末尾）" else ""}：\n$preview")
                    }
                }
            }
            "edit" -> "目标：${args.optString("path")}\n修改块：${args.optJSONArray("edits")?.length() ?: 0}"
            "bash" -> "命令：${args.optString("command")}"
            else -> args.toString(2).let { if (it.length > 2_000) it.take(2_000) + "\n… 参数已截断" else it }
        }
    }

    private fun parseEvent(seq: Long, value: JSONObject): PiEvent {
        val type = value.optString("type")
        return when (type) {
            "message_update" -> {
                val delta = value.optJSONObject("assistantMessageEvent") ?: JSONObject()
                val subtype = delta.optString("type")
                val text = when (subtype) {
                    "text_delta", "thinking_delta", "toolcall_delta" -> delta.optString("delta")
                    "toolcall_start" -> "正在准备工具：${delta.optString("toolName")}"
                    "toolcall_end" -> "工具参数准备完成：${delta.optJSONObject("toolCall")?.optString("name").orEmpty()}"
                    else -> ""
                }
                PiEvent(
                    seq, type, subtype, text,
                    toolCallId = delta.optString("id"),
                    contentIndex = delta.optInt("contentIndex", -1)
                )
            }
            "message_end" -> {
                val message = value.optJSONObject("message") ?: JSONObject()
                PiEvent(
                    seq, type, message.optString("role"), messageText(message),
                    stopReason = message.optString("stopReason")
                )
            }
            "tool_execution_start" -> {
                val args = value.optJSONObject("args")
                val toolName = value.optString("toolName")
                val details = toolArgsText(toolName, args)
                val text = buildString {
                    append("执行工具：$toolName")
                    if (details.isNotBlank()) append("\n\n$details")
                }
                PiEvent(seq, type, "", text, toolCallId = value.optString("toolCallId"))
            }
            "tool_execution_update" -> {
                val text = value.optJSONObject("partialResult")
                    ?.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                PiEvent(seq, type, "", text, toolCallId = value.optString("toolCallId"))
            }
            "tool_execution_end" -> {
                val content = value.optJSONObject("result")?.optJSONArray("content") ?: JSONArray()
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
                val status = if (value.optBoolean("isError")) "工具执行失败" else "工具完成：${value.optString("toolName")}"
                PiEvent(
                    seq, type, "", status + if (output.isBlank()) "" else "\n\n$output",
                    toolCallId = value.optString("toolCallId")
                )
            }
            "stderr" -> PiEvent(seq, type, "", value.optString("text"))
            "process_exit" -> PiEvent(seq, type, "", "Pi 进程退出：${value.optString("code", value.optString("signal"))}\n${value.optString("stderr")}".trim())
            "extension_error" -> PiEvent(seq, type, "", value.optString("error", value.toString()))
            "extension_ui_request" -> {
                val optionsArray = value.optJSONArray("options") ?: JSONArray()
                val options = buildList { for (i in 0 until optionsArray.length()) add(optionsArray.optString(i)) }
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = value.optString("method"),
                    text = value.optString("message", value.optString("text", value.optString("statusText"))),
                    uiRequest = PiUiRequest(
                        id = value.optString("id"),
                        method = value.optString("method"),
                        title = value.optString("title"),
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

    private suspend fun runTermux(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").setClassName(termux, service)
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            context.startService(intent)
            Result.success(Unit)
        } catch (_: SecurityException) {
            Result.failure(IllegalStateException("请给 Pi Android 开启 Termux 的 RUN_COMMAND 权限，并确认 ~/.termux/termux.properties 中 allow-external-apps=true"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun request(path: String, body: String?, timeoutMs: Int = 5_000): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = timeoutMs.coerceAtMost(5_000)
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Authorization", "Bearer $authToken")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "HTTP $code: $text" })
            }
            text
        }
    }

    private fun loadOrCreateAuthToken(): String {
        val preferences = context.getSharedPreferences("bridge_security", Context.MODE_PRIVATE)
        preferences.getString("auth_token", null)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        check(preferences.edit().putString("auth_token", token).commit()) { "无法保存 Bridge 认证信息" }
        return token
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

data class PiHealth(val piRunning: Boolean, val cwd: String, val launchCommand: String, val stderr: String)
data class PiState(
    val provider: String,
    val modelId: String,
    val modelName: String,
    val thinkingLevel: String,
    val streaming: Boolean,
    val compacting: Boolean,
    val sessionFile: String,
    val sessionId: String,
    val sessionName: String,
    val messageCount: Int,
    val autoCompactionEnabled: Boolean
)
data class PiStats(
    val sessionFile: String,
    val sessionId: String,
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
data class PiAttachment(val name: String, val mimeType: String, val data: String, val byteCount: Int)
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
    val stopReason: String = ""
)
data class PiEventBatch(val events: List<PiEvent>, val latest: Long, val gap: Boolean)
data class PiFile(val name: String, val type: String, val path: String)
