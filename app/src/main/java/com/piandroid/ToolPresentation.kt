package com.piandroid

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Keep tool arguments lossless. Collapsing belongs to the UI, never to parsing/history. */
internal fun formatToolArgs(toolName: String, args: JSONObject?): String {
    if (args == null || args.length() == 0) return ""

    fun path(): String = args.optString("path")
    fun pretty(value: Any?): String = when (value) {
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        null -> ""
        else -> value.toString()
    }
    fun extras(excluded: Set<String>): String {
        val extra = JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in excluded) extra.put(key, args.opt(key))
        }
        return if (extra.length() > 0) extra.toString(2) else ""
    }
    fun join(summary: String, body: String = "", extra: String = ""): String =
        listOf(summary.trimEnd(), body.trimEnd(), extra.trimEnd()).filter { it.isNotBlank() }.joinToString("\n")

    return when (toolName) {
        "bash", "powershell" -> {
            val command = args.optString("command")
            val timeout = args.optInt("timeout", 0)
            val summary = buildString {
                append(command)
                if (timeout > 0) append("  (${timeout}s timeout)")
            }
            join(summary, extra = extras(setOf("command", "timeout")))
        }
        "read" -> {
            val offset = args.optInt("offset", 0)
            val limit = args.optInt("limit", 0)
            val summary = buildString {
                append(path())
                if (offset > 0 || limit > 0) {
                    append("  [")
                    if (offset > 0) append("offset $offset")
                    if (offset > 0 && limit > 0) append(", ")
                    if (limit > 0) append("limit $limit")
                    append(']')
                }
            }
            join(summary, extra = extras(setOf("path", "offset", "limit")))
        }
        "write" -> {
            val content = args.optString("content")
            val summary = buildString {
                append(path())
                if (content.isNotEmpty()) append("  ·  ${content.length} chars")
            }
            join(summary, content, extras(setOf("path", "content")))
        }
        "edit" -> {
            val edits = args.optJSONArray("edits")
            val legacyOld = args.optString("oldText")
            val legacyNew = args.optString("newText")
            val count = edits?.length() ?: if (legacyOld.isNotEmpty() || legacyNew.isNotEmpty()) 1 else 0
            val summary = buildString {
                append(path())
                if (count > 0) append("  ·  $count edits")
            }
            val body = when {
                edits != null && edits.length() > 0 -> pretty(edits)
                legacyOld.isNotEmpty() || legacyNew.isNotEmpty() -> JSONObject()
                    .put("oldText", legacyOld)
                    .put("newText", legacyNew)
                    .toString(2)
                else -> ""
            }
            join(summary, body, extras(setOf("path", "edits", "oldText", "newText")))
        }
        "grep" -> {
            val pattern = args.optString("pattern")
            val searchPath = args.optString("path")
            val glob = args.optString("glob")
            val ignoreCase = args.optBoolean("ignoreCase", false)
            val literal = args.optBoolean("literal", false)
            val context = args.optInt("context", 0)
            val limit = args.optInt("limit", 0)
            val summary = buildString {
                append(pattern)
                if (searchPath.isNotBlank()) append("  $searchPath")
                if (glob.isNotBlank()) append("  ($glob)")
                if (ignoreCase) append("  ignore-case")
                if (literal) append("  literal")
                if (context > 0) append("  context $context")
                if (limit > 0) append("  limit $limit")
            }
            join(summary, extra = extras(setOf("pattern", "path", "glob", "ignoreCase", "literal", "context", "limit")))
        }
        "find" -> {
            val pattern = args.optString("pattern", args.optString("query"))
            val searchPath = args.optString("path")
            val limit = args.optInt("limit", 0)
            val summary = buildString {
                append(pattern)
                if (searchPath.isNotBlank()) append("  $searchPath")
                if (limit > 0) append("  (limit $limit)")
            }
            join(summary, extra = extras(setOf("pattern", "query", "path", "limit")))
        }
        "ls" -> {
            val limit = args.optInt("limit", 0)
            val summary = buildString {
                append(path())
                if (limit > 0) append("  (limit $limit)")
            }
            join(summary, extra = extras(setOf("path", "limit")))
        }
        "subagent" -> {
            val tasks = args.optJSONArray("tasks")
            val summary = if (tasks != null && tasks.length() > 0) {
                val agents = buildList {
                    for (i in 0 until tasks.length()) {
                        tasks.optJSONObject(i)?.optString("agent")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.distinct()
                if (tasks.length() == 1) agents.firstOrNull().orEmpty()
                else "${tasks.length()} tasks${if (agents.isNotEmpty()) " · ${agents.joinToString(", ")}" else ""}"
            } else {
                args.optString("agent")
            }
            // Keep the compact first line, but make Show all reveal every delegated task/config field.
            join(summary, args.toString(2))
        }
        else -> args.toString(2)
    }
}

/** Keep the tool result payload lossless; presentation/state belongs to the UI. */
internal fun toolResultText(
    toolName: String,
    output: String,
    isError: Boolean,
    diff: String = ""
): String {
    val sections = mutableListOf<String>()
    if (!isError && toolName == "edit" && diff.isNotBlank() && !output.contains(diff)) {
        sections += diff
    }
    if (output.isNotBlank()) sections += output
    if (isError && sections.isEmpty()) sections += "Error"
    return sections.joinToString("\n\n")
}

/**
 * Tool output follows Pi's compact terminal convention: keep the tail because it
 * usually contains the result/error, and let the UI reveal the full lossless text.
 */
internal fun toolOutputPreview(output: String, maxLines: Int = 5, maxChars: Int = 700): String {
    val clean = output.trimEnd()
    if (clean.isBlank()) return ""
    val lines = clean.lines()
    var preview = if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else clean
    if (preview.length > maxChars) preview = "…\n" + preview.takeLast(maxChars)
    return preview
}

internal fun toolHiddenHint(output: String, maxLines: Int = 5, maxChars: Int = 700): String {
    val clean = output.trimEnd()
    if (clean.isBlank()) return ""
    val lineCount = clean.lines().size
    return when {
        lineCount > maxLines -> "… +${lineCount - maxLines} lines"
        clean.length > maxChars -> "… +${clean.length - maxChars} chars"
        else -> ""
    }
}

/**
 * Arguments are different from output: the useful part is normally the beginning
 * (command/path/pattern), so collapsed cards keep the head rather than the tail.
 * Compose also applies maxLines, which handles a single very long command that
 * visually wraps on a narrow phone even when it contains no newline characters.
 */
internal fun toolArgsPreview(args: String, maxLines: Int = 2, maxChars: Int = 180): String {
    val clean = args.trimEnd()
    if (clean.isBlank()) return ""
    val lines = clean.lines()
    var preview = if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") else clean
    if (preview.length > maxChars) preview = preview.take(maxChars).trimEnd() + "…"
    return preview
}

internal fun toolArgsHiddenHint(args: String, maxLines: Int = 2, maxChars: Int = 180): String {
    val clean = args.trimEnd()
    if (clean.isBlank()) return ""
    val lineCount = clean.lines().size
    return when {
        lineCount > maxLines -> "… +${lineCount - maxLines} arg lines"
        clean.length > maxChars -> "… +${clean.length - maxChars} arg chars"
        else -> ""
    }
}

internal fun formatToolDuration(durationMs: Long): String =
    String.format(Locale.US, "%.1fs", durationMs.coerceAtLeast(0L) / 1000.0)

internal fun assistantCompletionNotice(
    stopReason: String,
    text: String,
    errorMessage: String
): String? = when {
    stopReason == "error" && errorMessage.isNotBlank() -> "模型错误：$errorMessage"
    stopReason == "aborted" && text.isBlank() -> "本轮任务已中止"
    else -> null
}
