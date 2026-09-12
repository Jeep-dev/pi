package com.piandroid

import java.util.Locale

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

internal fun toolOutputPreview(output: String, maxLines: Int = 5, maxChars: Int = 1200): String {
    val clean = output.trimEnd()
    if (clean.isBlank()) return ""
    val lines = clean.lines()
    var preview = if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else clean
    if (preview.length > maxChars) preview = "…\n" + preview.takeLast(maxChars)
    return preview
}

internal fun toolHiddenHint(output: String, maxLines: Int = 5, maxChars: Int = 1200): String {
    val clean = output.trimEnd()
    if (clean.isBlank()) return ""
    val lineCount = clean.lines().size
    return when {
        lineCount > maxLines -> "… (${lineCount - maxLines} earlier lines)"
        clean.length > maxChars -> "… (${clean.length - maxChars} earlier chars)"
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
