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
