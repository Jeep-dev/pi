package com.piandroid

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PiBridge(private val context: Context) {
    private val termux = "com.termux"
    private val service = "com.termux.app.RunCommandService"
    private val port = 17642
    private var nextId = 2000

    fun termuxAvailable() = runCatching { context.packageManager.getPackageInfo(termux, 0) }.isSuccess

    suspend fun installAndStartBridge(cwd: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!termuxAvailable()) return@withContext Result.failure(IllegalStateException("请先安装 Termux"))
        val asset = context.assets.open("pi-android-bridge.mjs").use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val safeCwd = cwd.replace("'", "'\\''")
        val command = "mkdir -p ~/.pi/android && echo $asset | base64 -d > ~/.pi/android/bridge.mjs && chmod 700 ~/.pi/android/bridge.mjs && PI_ANDROID_CWD='$safeCwd' nohup node ~/.pi/android/bridge.mjs >/tmp/pi-android-bridge.log 2>&1 &"
        runTermux(command)
    }

    suspend fun start(cwd: String): Result<String> = request("/start", "{\"cwd\":${json(cwd)}}")
    suspend fun prompt(message: String): Result<String> = request("/prompt", "{\"message\":${json(message)}}")
    suspend fun terminal(command: String): Result<String> = request("/terminal", "{\"command\":${json(command)}}")
    suspend fun files(path: String = ""): Result<List<PiFile>> = request("/files?path=${encode(path)}", null).map { body ->
        val array = JSONObject(body).optJSONArray("entries")
        buildList { if (array != null) for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(PiFile(item.optString("name"), item.optString("type"), item.optString("path")))
        } }
    }
    suspend fun file(path: String): Result<String> = request("/file?path=${encode(path)}", null).map { JSONObject(it).optString("content") }
    suspend fun writeFile(path: String, content: String): Result<Unit> = request("/file", "{\"path\":${json(path)},\"content\":${json(content)}}").map { Unit }
    suspend fun diff(): Result<String> = request("/diff", null).map { JSONObject(it).optString("diff") }
    suspend fun events(after: Long): Result<PiEventBatch> = withContext(Dispatchers.IO) {
        request("/events?after=$after", null).map { body ->
            val root = JSONObject(body)
            val array = root.optJSONArray("events")
            val parsed = buildList {
                if (array != null) for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val value = item.optJSONObject("value") ?: JSONObject().put("type", "raw").put("line", item.optString("value"))
                    add(PiEvent(item.optLong("seq"), value.optString("type"), renderEvent(value)))
                }
            }
            PiEventBatch(parsed, root.optLong("latest", after))
        }
    }

    private suspend fun runTermux(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val callback = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(context, nextId++, callback, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            val intent = Intent("com.termux.RUN_COMMAND").setClassName(termux, service)
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            context.startService(intent)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException("请在系统设置中给 Pi Android 开启“在 Termux 环境中运行命令”权限"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun request(path: String, body: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"; connectTimeout = 1500; readTimeout = 3000
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body.toByteArray()) } }
            }
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (connection.responseCode in 200..299) Result.success(text) else Result.failure(IllegalStateException(text))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun renderEvent(value: JSONObject): String {
        return when (value.optString("type")) {
            "message_update" -> {
                val delta = value.optJSONObject("assistantMessageEvent") ?: return ""
                when (delta.optString("type")) {
                    "text_delta", "thinking_delta", "toolcall_delta" -> delta.optString("delta")
                    "toolcall_start" -> "调用工具：${delta.optString("toolName")}"
                    else -> ""
                }
            }
            "tool_execution_start" -> "执行工具：${value.optString("toolName")}"
            "tool_execution_update" -> value.optJSONObject("partialResult")?.optJSONArray("content")?.optJSONObject(0)?.optString("text", "") ?: ""
            "tool_execution_end" -> value.optBoolean("isError").let { if (it) "工具执行失败" else "工具完成：${value.optString("toolName")}" }
            "agent_start" -> "Pi 开始处理"
            "agent_settled" -> "本轮完成"
            "compaction_start" -> "正在压缩上下文"
            "compaction_end" -> "上下文压缩完成"
            "stderr" -> value.optString("text")
            "terminal_start" -> "$ ${value.optString("command")}"
            "terminal_output" -> value.optString("text")
            "terminal_end" -> "[进程结束，退出码：${value.optString("code", "-1")}]"
            else -> ""
        }
    }
}

data class PiEvent(val seq: Long, val type: String, val text: String)
data class PiEventBatch(val events: List<PiEvent>, val latest: Long)
data class PiFile(val name: String, val type: String, val path: String)
