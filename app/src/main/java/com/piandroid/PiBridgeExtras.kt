package com.piandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Session helpers share PiBridge's authenticated transport. */
private fun parseHistory(messages: JSONArray): List<PiHistoryMessage> = buildList {
    for (i in 0 until messages.length()) {
        val message = messages.optJSONObject(i) ?: continue
        val text = message.optString("text")
        if (text.isBlank()) continue
        add(
            PiHistoryMessage(
                role = message.optString("role", "system"),
                text = text,
                toolCallId = message.optString("toolCallId"),
                collapsed = message.optBoolean("collapsed", true)
            )
        )
    }
}

suspend fun PiBridge.history(): Result<List<PiHistoryMessage>> = request("/history", null, 70_000).mapCatching { raw ->
    parseHistory(JSONObject(raw).optJSONArray("history") ?: JSONArray())
}

suspend fun PiBridge.recoverySnapshot(): Result<PiRecoverySnapshot> = request("/snapshot", null, 70_000).mapCatching { raw ->
    val root = JSONObject(raw)
    val eventArray = root.optJSONArray("events") ?: JSONArray()
    val recoveredEvents = buildList {
        for (i in 0 until eventArray.length()) {
            val item = eventArray.optJSONObject(i) ?: continue
            val value = item.optJSONObject("value") ?: continue
            add(parseEvent(item.optLong("seq"), value))
        }
    }
    val pendingArray = root.optJSONArray("pendingUi") ?: JSONArray()
    val pendingUi = buildList {
        for (i in 0 until pendingArray.length()) {
            val value = pendingArray.optJSONObject(i) ?: continue
            parseEvent(0L, value).uiRequest?.let { add(it) }
        }
    }
    PiRecoverySnapshot(
        history = parseHistory(root.optJSONArray("history") ?: JSONArray()),
        events = recoveredEvents,
        latest = root.optLong("latest"),
        pendingUi = pendingUi,
        editorText = if (root.has("editorText") && !root.isNull("editorText")) root.optString("editorText") else null
    )
}

data class PiHistoryMessage(
    val role: String,
    val text: String,
    val toolCallId: String = "",
    val collapsed: Boolean = true
)

data class PiRecoverySnapshot(
    val history: List<PiHistoryMessage>,
    val events: List<PiEvent>,
    val latest: Long,
    val pendingUi: List<PiUiRequest>,
    val editorText: String?
)

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

suspend fun PiBridge.switchSession(sessionPath: String): Result<PiState> = withContext(Dispatchers.IO) {
    request(
        "/switch-session",
        JSONObject().put("path", sessionPath).toString(),
        70_000
    ).mapCatching { raw ->
        val state = JSONObject(raw).optJSONObject("state")
            ?: throw IllegalStateException("Pi did not return the switched session state")
        parseState(state)
    }
}

data class PiSession(
    val path: String,
    val id: String,
    val title: String,
    val modified: Long,
    val current: Boolean
)
