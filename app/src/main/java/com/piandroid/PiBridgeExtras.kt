package com.piandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun normalizeRecoveredToolArgs(toolName: String, raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""

    fun valueAfter(vararg prefixes: String): String? = text.lineSequence()
        .map(String::trim)
        .firstNotNullOfOrNull { line ->
            prefixes.firstNotNullOfOrNull { prefix ->
                line.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
            }
        }

    // New bridge histories store the complete raw arguments as JSON. Use the exact
    // same formatter as live tool events so restart/resume never changes the card.
    runCatching { JSONObject(text) }.getOrNull()?.let { return formatToolArgs(toolName, it) }

    return when (toolName) {
        "bash" -> valueAfter("命令：", "命令:") ?: text
        "edit" -> {
            val target = valueAfter("目标：", "目标:") ?: return text
            val count = valueAfter("修改块：", "修改块:")?.toIntOrNull() ?: 0
            buildString {
                append(target)
                if (count > 0) append("  ·  $count edits")
            }
        }
        "write" -> {
            val target = valueAfter("目标：", "目标:") ?: return text
            val count = valueAfter("内容：", "内容:")
                ?.substringBefore(' ')
                ?.toIntOrNull()
                ?: 0
            val preview = sequenceOf("写入预览（末尾）：", "写入预览（末尾）:", "写入预览：", "写入预览:")
                .mapNotNull { marker -> text.indexOf(marker).takeIf { it >= 0 }?.let { text.substring(it + marker.length).trimStart() } }
                .firstOrNull()
                .orEmpty()
            buildString {
                append(target)
                if (count > 0) append("  ·  $count chars")
                if (preview.isNotBlank()) append('\n').append(preview)
            }
        }
        else -> text
    }
}

/** Session helpers share PiBridge's authenticated transport. */
private fun parseHistory(messages: JSONArray): List<PiHistoryMessage> = buildList {
    for (i in 0 until messages.length()) {
        val message = messages.optJSONObject(i) ?: continue
        val role = message.optString("role", "system")
        val toolName = message.optString("toolName")
        val toolOutput = message.optString("toolOutput")
        val toolArgs = if (role == "tool") {
            normalizeRecoveredToolArgs(toolName, message.optString("toolArgs"))
        } else {
            message.optString("toolArgs")
        }
        val originalText = message.optString("text")
        val text = if (role == "tool" && toolName.isNotBlank()) {
            buildString {
                append(toolName)
                if (toolArgs.isNotBlank()) append(' ').append(toolArgs)
                if (toolOutput.isNotBlank()) append("\n\n").append(toolOutput)
            }
        } else {
            originalText
        }
        if (text.isBlank()) continue
        add(
            PiHistoryMessage(
                role = role,
                text = text,
                toolCallId = message.optString("toolCallId"),
                collapsed = message.optBoolean("collapsed", true),
                tokensBefore = message.optLong("tokensBefore"),
                toolName = toolName,
                toolArgs = toolArgs,
                toolOutput = toolOutput,
                toolIsError = message.optBoolean("toolIsError", false),
                toolDurationMs = if (message.has("toolDurationMs")) message.optLong("toolDurationMs", -1L) else -1L
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
    val collapsed: Boolean = true,
    val tokensBefore: Long = 0,
    val toolName: String = "",
    val toolArgs: String = "",
    val toolOutput: String = "",
    val toolIsError: Boolean = false,
    val toolDurationMs: Long = -1L
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
                    piConversationId = item.optString("id"),
                    title = item.optString("title").ifBlank { "未命名 session" },
                    modified = item.optLong("modified"),
                    current = item.optBoolean("current")
                )
            )
        }
    }
}

suspend fun PiBridge.switchSession(
    runtimeOwnerSessionId: String,
    targetPiConversationId: String,
    sessionPath: String
): Result<PiState> = withContext(Dispatchers.IO) {
    request(
        "/switch-session",
        JSONObject()
            .put("androidSessionId", runtimeOwnerSessionId)
            .put("piConversationId", targetPiConversationId)
            .put("path", sessionPath)
            .toString(),
        70_000
    ).mapCatching { raw ->
        val state = JSONObject(raw).optJSONObject("state")
            ?: throw IllegalStateException("Pi did not return the switched session state")
        parseState(state).also { switched ->
            check(
                targetPiConversationId.isBlank() || switched.piConversationId == targetPiConversationId
            ) {
                "Pi conversation identity mismatch: expected=$targetPiConversationId actual=${switched.piConversationId}"
            }
        }
    }
}

data class PiSession(
    val path: String,
    val piConversationId: String,
    val title: String,
    val modified: Long,
    val current: Boolean
)
