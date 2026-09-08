package com.piandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small read-only helpers that do not need to live inside PiBridge itself. */
suspend fun PiBridge.history(): Result<List<PiHistoryMessage>> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL("http://127.0.0.1:17643/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 8000
            useCaches = false
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $raw")
        val data = JSONObject(raw).optJSONObject("data") ?: JSONObject()
        val messages = data.optJSONArray("messages") ?: JSONArray()
        buildList {
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                val role = message.optString("role")
                if (role != "user" && role != "assistant") continue
                val text = visibleText(message.opt("content"), role == "assistant")
                if (text.isNotBlank()) add(PiHistoryMessage(role, text))
            }
        }
    }
}

private fun visibleText(content: Any?, assistant: Boolean): String {
    return when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                val type = part.optString("type")
                if (type == "text" || (!assistant && type.isBlank())) {
                    val text = part.optString("text")
                    if (text.isNotBlank()) append(text)
                }
            }
        }
        else -> ""
    }
}

data class PiHistoryMessage(val role: String, val text: String)
