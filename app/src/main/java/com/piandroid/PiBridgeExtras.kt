package com.piandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Session helpers share PiBridge's authenticated transport. */
suspend fun PiBridge.history(): Result<List<PiHistoryMessage>> = request("/messages", null, 8_000).mapCatching { raw ->
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

private fun visibleText(content: Any?, assistant: Boolean): String = when (content) {
    is String -> content
    is JSONArray -> buildString {
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val type = part.optString("type")
            if (type == "text" || (!assistant && type.isBlank())) {
                part.optString("text").takeIf(String::isNotBlank)?.let(::append)
            } else if (!assistant && type == "image") {
                if (isNotEmpty()) append('\n')
                append("[图片附件]")
            }
        }
    }
    else -> ""
}

data class PiHistoryMessage(val role: String, val text: String)

suspend fun PiBridge.sessions(): Result<List<PiSession>> = request("/sessions", null, 12_000).mapCatching { raw ->
    val array = JSONObject(raw).optJSONArray("sessions") ?: JSONArray()
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                PiSession(
                    path = item.optString("path"),
                    id = item.optString("id"),
                    title = item.optString("title").ifBlank { "未命名 session" },
                    modified = item.optLong("modified"),
                    current = item.optBoolean("current")
                )
            )
        }
    }
}

suspend fun PiBridge.switchSession(sessionPath: String): Result<Unit> = withContext(Dispatchers.IO) {
    request(
        "/switch-session",
        JSONObject().put("path", sessionPath).toString(),
        35_000
    ).map { Unit }
}

data class PiSession(
    val path: String,
    val id: String,
    val title: String,
    val modified: Long,
    val current: Boolean
)
