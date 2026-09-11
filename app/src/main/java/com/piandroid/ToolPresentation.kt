package com.piandroid

/** Build the complete text shown by the existing expandable tool card. */
internal fun toolResultText(
    toolName: String,
    output: String,
    isError: Boolean,
    diff: String = ""
): String {
    val sections = mutableListOf(
        if (isError) "工具执行失败：$toolName" else "工具完成：$toolName"
    )
    if (!isError && toolName == "edit" && diff.isNotBlank() && !output.contains(diff)) {
        sections += diff
    }
    if (output.isNotBlank()) sections += output
    return sections.joinToString("\n\n")
}

internal fun assistantCompletionNotice(
    stopReason: String,
    text: String,
    errorMessage: String
): String? = when {
    stopReason == "error" && errorMessage.isNotBlank() -> "模型错误：$errorMessage"
    stopReason == "aborted" && text.isBlank() -> "本轮任务已中止"
    else -> null
}
