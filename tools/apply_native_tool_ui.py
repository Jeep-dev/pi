from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch site: {label}")
    return text.replace(old, new, 1)

# --- Tool presentation helpers ---
presentation = Path("app/src/main/java/com/piandroid/ToolPresentation.kt")
presentation.write_text(r'''package com.piandroid

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
''')

# --- Theme: mirror Pi's native tool-state palette ---
theme = Path("app/src/main/java/com/piandroid/PiTheme.kt")
text = theme.read_text()
text = replace_once(text,
'''    val toolBg: Color,\n    val userBg: Color,''',
'''    val toolBg: Color,\n    val toolPendingBg: Color,\n    val toolSuccessBg: Color,\n    val toolErrorBg: Color,\n    val toolTitle: Color,\n    val toolOutput: Color,\n    val toolMeta: Color,\n    val toolDiffAdded: Color,\n    val toolDiffRemoved: Color,\n    val userBg: Color,''',
"PiColors tool fields")
text = replace_once(text,
'''    toolBg = Color(0xFF263229),\n    userBg = Color(0xFF30313A),''',
'''    toolBg = Color(0xFF263229),\n    toolPendingBg = Color(0xFF282832),\n    toolSuccessBg = Color(0xFF283228),\n    toolErrorBg = Color(0xFF3C2828),\n    toolTitle = Color(0xFFD4D4D4),\n    toolOutput = Color(0xFF808080),\n    toolMeta = Color(0xFF666666),\n    toolDiffAdded = Color(0xFFB5BD68),\n    toolDiffRemoved = Color(0xFFCC6666),\n    userBg = Color(0xFF30313A),''',
"dark tool palette")
text = replace_once(text,
'''    toolBg = Color(0xFFE5F1E8),\n    userBg = Color(0xFFE7EDF5),''',
'''    toolBg = Color(0xFFE5F1E8),\n    toolPendingBg = Color(0xFFF0F0F4),\n    toolSuccessBg = Color(0xFFE7F2E7),\n    toolErrorBg = Color(0xFFF7E6E6),\n    toolTitle = Color(0xFF202124),\n    toolOutput = Color(0xFF666A70),\n    toolMeta = Color(0xFF7A7F85),\n    toolDiffAdded = Color(0xFF2E7D32),\n    toolDiffRemoved = Color(0xFFC62828),\n    userBg = Color(0xFFE7EDF5),''',
"light tool palette")
text = replace_once(text,
'''    toolBg = Color(0xFF343A37),\n    userBg = Color(0xFF3B3D43),''',
'''    toolBg = Color(0xFF343A37),\n    toolPendingBg = Color(0xFF33343A),\n    toolSuccessBg = Color(0xFF303A32),\n    toolErrorBg = Color(0xFF423132),\n    toolTitle = Color(0xFFF0F1F2),\n    toolOutput = Color(0xFFA9AEB4),\n    toolMeta = Color(0xFF8C9197),\n    toolDiffAdded = Color(0xFFB7D8C0),\n    toolDiffRemoved = Color(0xFFE5A0A0),\n    userBg = Color(0xFF3B3D43),''',
"gray tool palette")
theme.write_text(text)

# --- Bridge: keep events structured instead of flattening them into Chinese prose ---
bridge = Path("app/src/main/java/com/piandroid/PiBridge.kt")
text = bridge.read_text()
old_args = r'''    private fun toolArgsText(toolName: String, args: JSONObject?): String {
        if (args == null || args.length() == 0) return ""
        return when (toolName) {
            "write" -> {
                val content = args.optString("content")
                buildString {
                    append("目标：${args.optString("path")}\n")
                    append("内容：${content.length} 字符")
                    if (content.isNotBlank()) {
                        val preview = content.takeLast(1_600)
                        append("\n\n写入预览${if (content.length > preview.length) "（末尾）" else ""}：\n$preview")
                    }
                }
            }
            "edit" -> "目标：${args.optString("path")}\n修改块：${args.optJSONArray("edits")?.length() ?: 0}"
            "bash" -> "命令：${args.optString("command")}"
            else -> args.toString(2).let { if (it.length > 4_000) it.take(4_000) + "\n… 参数显示已截断" else it }
        }
    }
'''
new_args = r'''    private fun toolArgsText(toolName: String, args: JSONObject?): String {
        if (args == null || args.length() == 0) return ""
        fun path(): String = args.optString("path")
        return when (toolName) {
            "bash" -> buildString {
                append(args.optString("command"))
                val timeout = args.optInt("timeout", 0)
                if (timeout > 0) append("  (${timeout}s timeout)")
            }
            "read" -> buildString {
                append(path())
                val offset = args.optInt("offset", 0)
                val limit = args.optInt("limit", 0)
                if (offset > 0 || limit > 0) append("  [${if (offset > 0) "offset $offset" else ""}${if (offset > 0 && limit > 0) ", " else ""}${if (limit > 0) "limit $limit" else ""}]")
            }
            "write" -> buildString {
                append(path())
                val count = args.optString("content").length
                if (count > 0) append("  ·  $count chars")
            }
            "edit" -> buildString {
                append(path())
                val count = args.optJSONArray("edits")?.length() ?: 0
                if (count > 0) append("  ·  $count edits")
            }
            "grep" -> buildString {
                append(args.optString("pattern"))
                args.optString("path").takeIf { it.isNotBlank() }?.let { append("  $it") }
            }
            "find" -> buildString {
                append(args.optString("pattern", args.optString("query")))
                args.optString("path").takeIf { it.isNotBlank() }?.let { append("  $it") }
            }
            "ls" -> path()
            "subagent" -> {
                val tasks = args.optJSONArray("tasks")
                if (tasks != null && tasks.length() > 0) {
                    val agents = buildList {
                        for (i in 0 until tasks.length()) {
                            tasks.optJSONObject(i)?.optString("agent")?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }.distinct()
                    if (tasks.length() == 1) agents.firstOrNull().orEmpty() else "${tasks.length()} tasks${if (agents.isNotEmpty()) " · ${agents.joinToString(", ")}" else ""}"
                } else {
                    args.optString("agent")
                }
            }
            else -> args.toString().replace('\n', ' ').let { if (it.length > 800) it.take(800) + " …" else it }
        }
    }

    private fun compactToolNumber(value: Long): String = when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fm", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    private fun subagentMeta(details: JSONObject?): String {
        if (details?.optString("kind") != "pi-subagent-progress") return ""
        val runs = details.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                if (isNotEmpty()) append('\n')
                val status = run.optString("status")
                val icon = when (status) {
                    "succeeded" -> "✓"
                    "failed", "error" -> "✗"
                    "running" -> "●"
                    else -> "○"
                }
                append(icon).append(' ').append(run.optString("agent", "subagent"))
                run.optString("model").substringAfter('/').takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                val usage = run.optJSONObject("usage")
                val turns = usage?.optInt("turns", 0) ?: 0
                val ctx = usage?.optLong("ctxTokens", 0L) ?: 0L
                val cost = usage?.optDouble("cost", 0.0) ?: 0.0
                if (turns > 0) append(" · ").append(turns).append(if (turns == 1) " turn" else " turns")
                if (ctx > 0) append(" · ").append(compactToolNumber(ctx)).append(" ctx")
                if (cost > 0.0) append(" · $").append(String.format(java.util.Locale.US, "%.4f", cost))
            }
        }
    }
'''
text = replace_once(text, old_args, new_args, "tool argument formatter")
text = text.replace('''                    "toolcall_start" -> "正在准备工具：${delta.optString("toolName")}"\n                    "toolcall_end" -> "工具参数准备完成：${delta.optJSONObject("toolCall")?.optString("name").orEmpty()}"''', '''                    "toolcall_start" -> delta.optString("toolName")\n                    "toolcall_end" -> delta.optJSONObject("toolCall")?.optString("name").orEmpty()''')
text = replace_once(text,
'''                    toolCallId = delta.optString("id"),\n                    contentIndex = delta.optInt("contentIndex", -1)\n                )''',
'''                    toolCallId = delta.optString("id"),\n                    contentIndex = delta.optInt("contentIndex", -1),\n                    toolName = delta.optString("toolName", delta.optJSONObject("toolCall")?.optString("name").orEmpty())\n                )''',
"message update tool name")
old_start = r'''            "tool_execution_start" -> {
                val args = value.optJSONObject("args")
                val toolName = value.optString("toolName")
                val details = toolArgsText(toolName, args)
                val text = buildString {
                    append("执行工具：$toolName")
                    if (details.isNotBlank()) append("\n\n$details")
                }
                PiEvent(seq, type, "", text, toolCallId = value.optString("toolCallId"))
            }
'''
new_start = r'''            "tool_execution_start" -> {
                val args = value.optJSONObject("args")
                val toolName = value.optString("toolName")
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = "",
                    toolCallId = value.optString("toolCallId"),
                    argsText = toolArgsText(toolName, args),
                    toolName = toolName
                )
            }
'''
text = replace_once(text, old_start, new_start, "tool start parser")
old_update = r'''                val toolName = value.optString("toolName")
                PiEvent(
                    seq, type, "", text,
                    toolCallId = value.optString("toolCallId"),
                    argsText = buildString {
                        append("执行工具：$toolName")
                        toolArgsText(toolName, value.optJSONObject("args")).takeIf { it.isNotBlank() }?.let { append("\n\n$it") }
                    }
                )
'''
new_update = r'''                val toolName = value.optString("toolName")
                val meta = subagentMeta(value.optJSONObject("partialResult")?.optJSONObject("details"))
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = if (meta.isNotBlank() && text.trim() == "subagent running") "" else text,
                    toolCallId = value.optString("toolCallId"),
                    argsText = toolArgsText(toolName, value.optJSONObject("args")),
                    toolName = toolName,
                    metaText = meta
                )
'''
text = replace_once(text, old_update, new_update, "tool update parser")
old_end = r'''                val name = value.optString("toolName")
                PiEvent(
                    seq, type, "",
                    toolResultText(
                        toolName = name,
                        output = output,
                        isError = value.optBoolean("isError"),
                        diff = result?.optJSONObject("details")?.optString("diff").orEmpty()
                    ),
                    toolCallId = value.optString("toolCallId")
                )
'''
new_end = r'''                val name = value.optString("toolName")
                val details = result?.optJSONObject("details")
                PiEvent(
                    seq = seq,
                    type = type,
                    subtype = "",
                    text = toolResultText(
                        toolName = name,
                        output = output,
                        isError = value.optBoolean("isError"),
                        diff = details?.optString("diff").orEmpty()
                    ),
                    toolCallId = value.optString("toolCallId"),
                    toolName = name,
                    metaText = subagentMeta(details),
                    isError = value.optBoolean("isError")
                )
'''
text = replace_once(text, old_end, new_end, "tool end parser")
text = replace_once(text,
'''    val followUpQueue: List<String> = emptyList(),\n    val argsText: String = ""\n)''',
'''    val followUpQueue: List<String> = emptyList(),\n    val argsText: String = "",\n    val toolName: String = "",\n    val metaText: String = "",\n    val isError: Boolean = false\n)''',
"PiEvent tool fields")
bridge.write_text(text)

# --- Main UI: native-style structured tool cards + execution timing ---
main = Path("app/src/main/java/com/piandroid/MainActivity.kt")
text = main.read_text()
text = replace_once(text,
'''    val delivery: String = "normal",\n    val tokensBefore: Long = 0\n)''',
'''    val delivery: String = "normal",\n    val tokensBefore: Long = 0,\n    val toolName: String = "",\n    val toolArgs: String = "",\n    val toolOutput: String = "",\n    val toolMeta: String = "",\n    val toolIsError: Boolean = false,\n    val toolStartedAt: Long = 0L,\n    val toolEndedAt: Long = 0L\n)''',
"ChatLine tool state")
old_tool_functions = r'''    fun startToolDraft(contentIndex: Int, toolCallId: String, text: String) {
        if (contentIndex < 0) return
        toolDraftChars[contentIndex] = 0
        toolDraftBuffers[contentIndex] = StringBuilder()
        lines.add(
            ChatLine(
                role = "tool-draft",
                text = "$text\n\n正在生成调用参数…",
                streaming = true,
                toolCallId = toolCallId,
                contentIndex = contentIndex,
                collapsed = true
            )
        )
    }

    fun updateToolDraft(contentIndex: Int, delta: String) {
        if (contentIndex < 0) return
        val previous = toolDraftChars[contentIndex] ?: 0
        val current = previous + delta.length
        toolDraftChars[contentIndex] = current
        val buffer = toolDraftBuffers.getOrPut(contentIndex) { StringBuilder() }.append(delta)
        val now = android.os.SystemClock.uptimeMillis()
        val lastRefresh = toolDraftRefreshAt[contentIndex] ?: 0L
        if (now - lastRefresh < 50) return
        toolDraftRefreshAt[contentIndex] = now
        val index = lines.indexOfLast { it.role == "tool-draft" && it.contentIndex == contentIndex }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(text = line.text.substringBefore("\n\n") + "\n\n" + toolDraftPreview(buffer.toString(), current))
        }
    }

    fun finishToolDraft(contentIndex: Int, text: String) {
        val count = toolDraftChars.remove(contentIndex) ?: 0
        val raw = toolDraftBuffers.remove(contentIndex)?.toString().orEmpty()
        toolDraftRefreshAt.remove(contentIndex)
        val index = lines.indexOfLast { it.role == "tool-draft" && it.contentIndex == contentIndex }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(
                text = "$text\n\n${toolDraftPreview(raw, count)}\n\n参数完整，等待执行…",
                streaming = false
            )
        }
    }

    fun startTool(toolCallId: String, text: String) {
        val existingIndex = lines.indexOfLast {
            it.toolCallId == toolCallId && (it.role == "tool-draft" || it.role == "tool")
        }
        if (existingIndex >= 0) {
            val existing = lines[existingIndex]
            lines[existingIndex] = ChatLine("tool", text, streaming = true, toolCallId = toolCallId, collapsed = existing.collapsed)
        } else {
            lines.add(ChatLine("tool", text, streaming = true, toolCallId = toolCallId, collapsed = true))
        }
    }

    fun updateTool(toolCallId: String, text: String, argsText: String = "") {
        if (text.isBlank()) return
        val refreshKey = toolCallId.ifBlank { "__active_tool__" }
        val now = android.os.SystemClock.uptimeMillis()
        val lastRefresh = toolOutputRefreshAt[refreshKey] ?: 0L
        if (now - lastRefresh < 50L) return
        toolOutputRefreshAt[refreshKey] = now
        var index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index < 0 && argsText.isNotBlank()) {
            startTool(toolCallId, argsText)
            index = lines.indexOfLast {
                it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
            }
        }
        if (index >= 0) {
            val line = lines[index]
            val header = line.text.substringBefore("\n\n工具输出：")
            lines[index] = line.copy(text = "$header\n\n工具输出：\n${text.trimEnd()}")
        } else {
            lines.add(ChatLine("tool", text.trimEnd(), streaming = true, toolCallId = toolCallId, collapsed = true))
        }
    }

    fun finishTool(toolCallId: String, text: String) {
        toolOutputRefreshAt.remove(toolCallId.ifBlank { "__active_tool__" })
        var index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index < 0 && toolCallId.isNotBlank()) {
            index = lines.indexOfLast { it.role == "tool" && it.toolCallId == toolCallId }
        }
        if (index >= 0) {
            val line = lines[index]
            val header = line.text.substringBefore("\n\n工具输出：").trimEnd()
            val suffix = if (text.isBlank()) "" else "\n\n${text.trim()}"
            lines[index] = line.copy(text = header + suffix, streaming = false, collapsed = true)
        } else if (text.isNotBlank()) {
            lines.add(ChatLine("tool", text.trim(), toolCallId = toolCallId, collapsed = true))
        }
    }
'''
new_tool_functions = r'''    fun startToolDraft(contentIndex: Int, toolCallId: String, toolName: String) {
        if (contentIndex < 0) return
        toolDraftChars[contentIndex] = 0
        toolDraftBuffers[contentIndex] = StringBuilder()
        lines.add(
            ChatLine(
                role = "tool-draft",
                text = "",
                streaming = true,
                toolCallId = toolCallId,
                contentIndex = contentIndex,
                collapsed = true,
                toolName = toolName
            )
        )
    }

    fun updateToolDraft(contentIndex: Int, delta: String) {
        if (contentIndex < 0) return
        toolDraftChars[contentIndex] = (toolDraftChars[contentIndex] ?: 0) + delta.length
        toolDraftBuffers.getOrPut(contentIndex) { StringBuilder() }.append(delta)
    }

    fun finishToolDraft(contentIndex: Int, toolName: String) {
        toolDraftChars.remove(contentIndex)
        toolDraftBuffers.remove(contentIndex)
        toolDraftRefreshAt.remove(contentIndex)
        val index = lines.indexOfLast { it.role == "tool-draft" && it.contentIndex == contentIndex }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(toolName = toolName.ifBlank { line.toolName }, streaming = true)
        }
    }

    fun startTool(event: PiEvent) {
        val toolCallId = event.toolCallId
        val startedAt = android.os.SystemClock.uptimeMillis()
        val existingIndex = lines.indexOfLast {
            it.toolCallId == toolCallId && (it.role == "tool-draft" || it.role == "tool")
        }
        val next = ChatLine(
            role = "tool",
            text = "",
            streaming = true,
            toolCallId = toolCallId,
            collapsed = true,
            toolName = event.toolName,
            toolArgs = event.argsText,
            toolStartedAt = startedAt
        )
        if (existingIndex >= 0) {
            val existing = lines[existingIndex]
            lines[existingIndex] = next.copy(collapsed = existing.collapsed)
        } else {
            lines.add(next)
        }
    }

    fun updateTool(event: PiEvent) {
        val toolCallId = event.toolCallId
        val refreshKey = toolCallId.ifBlank { "__active_tool__" }
        val now = android.os.SystemClock.uptimeMillis()
        val lastRefresh = toolOutputRefreshAt[refreshKey] ?: 0L
        if (now - lastRefresh < 50L) return
        toolOutputRefreshAt[refreshKey] = now
        var index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index < 0) {
            startTool(event)
            index = lines.indexOfLast {
                it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
            }
        }
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(
                toolName = event.toolName.ifBlank { line.toolName },
                toolArgs = event.argsText.ifBlank { line.toolArgs },
                toolOutput = event.text.trimEnd().ifBlank { line.toolOutput },
                toolMeta = event.metaText.ifBlank { line.toolMeta }
            )
        }
    }

    fun finishTool(event: PiEvent) {
        val toolCallId = event.toolCallId
        toolOutputRefreshAt.remove(toolCallId.ifBlank { "__active_tool__" })
        var index = lines.indexOfLast {
            it.role == "tool" && it.streaming && (toolCallId.isBlank() || it.toolCallId == toolCallId)
        }
        if (index < 0 && toolCallId.isNotBlank()) {
            index = lines.indexOfLast { it.role == "tool" && it.toolCallId == toolCallId }
        }
        val endedAt = android.os.SystemClock.uptimeMillis()
        if (index >= 0) {
            val line = lines[index]
            lines[index] = line.copy(
                streaming = false,
                toolName = event.toolName.ifBlank { line.toolName },
                toolOutput = event.text.trimEnd().ifBlank { line.toolOutput },
                toolMeta = event.metaText.ifBlank { line.toolMeta },
                toolIsError = event.isError,
                toolEndedAt = endedAt
            )
        } else {
            lines.add(
                ChatLine(
                    role = "tool",
                    text = "",
                    streaming = false,
                    toolCallId = toolCallId,
                    collapsed = true,
                    toolName = event.toolName,
                    toolOutput = event.text.trimEnd(),
                    toolMeta = event.metaText,
                    toolIsError = event.isError,
                    toolStartedAt = endedAt,
                    toolEndedAt = endedAt
                )
            )
        }
    }
'''
text = replace_once(text, old_tool_functions, new_tool_functions, "tool lifecycle functions")
text = text.replace('''                                text = lines[i].text.substringBefore("\\n\\n") + "\\n\\n已取消，工具未执行",\n                                streaming = false''', '''                                text = "",\n                                toolMeta = "Cancelled",\n                                streaming = false''')
text = text.replace('''            "tool_execution_start" -> startTool(event.toolCallId, event.text)\n            "tool_execution_update" -> updateTool(event.toolCallId, event.text, event.argsText)\n            "tool_execution_end" -> finishTool(event.toolCallId, event.text)''', '''            "tool_execution_start" -> startTool(event)\n            "tool_execution_update" -> updateTool(event)\n            "tool_execution_end" -> finishTool(event)''')
# Replace the current generic tool card with a native-Pi-like structured renderer.
old_card = r'''                "tool", "tool-draft" -> Column(
                    Modifier.fillMaxWidth().background(ToolBg, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 10.dp)
                ) {
                    Text(
                        visibleText,
                        color = if (fullText.contains("工具执行失败：")) Danger else TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = if (line.collapsed) 10 else Int.MAX_VALUE,
                        overflow = if (line.collapsed) TextOverflow.Ellipsis else TextOverflow.Clip
                    )
                    if (hasHiddenToolContent) {
                        TextButton(
                            onClick = { onToggleLine(lineIndex) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (line.collapsed) "展开全部 ↓" else "收起 ↑",
                                color = Blue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
'''
new_card = r'''                "tool", "tool-draft" -> {
                    val colors = LocalPiColors.current
                    var toolNow by remember(line.toolCallId, line.toolStartedAt) {
                        mutableLongStateOf(android.os.SystemClock.uptimeMillis())
                    }
                    LaunchedEffect(line.streaming, line.toolStartedAt) {
                        while (line.streaming && line.toolStartedAt > 0L) {
                            toolNow = android.os.SystemClock.uptimeMillis()
                            delay(250)
                        }
                    }
                    val toolOutput = line.toolOutput.ifBlank { fullText }
                    val hiddenHint = toolHiddenHint(toolOutput)
                    val renderedOutput = if (line.collapsed && hiddenHint.isNotBlank()) toolOutputPreview(toolOutput) else toolOutput
                    val duration = if (line.toolStartedAt > 0L) {
                        val end = if (line.toolEndedAt > 0L) line.toolEndedAt else toolNow
                        formatToolDuration(end - line.toolStartedAt)
                    } else ""
                    val background = when {
                        line.toolIsError -> colors.toolErrorBg
                        line.streaming -> colors.toolPendingBg
                        else -> colors.toolSuccessBg
                    }
                    Column(
                        Modifier.fillMaxWidth().background(background, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 9.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildAnnotatedString {
                                    val name = line.toolName.ifBlank { if (line.role == "tool-draft") "tool" else "tool" }
                                    pushStyle(SpanStyle(color = colors.toolTitle, fontWeight = FontWeight.Bold))
                                    append(if (name == "bash") "$ " else name)
                                    if (name != "bash" && line.toolArgs.isNotBlank()) append(" ")
                                    pop()
                                    if (line.toolArgs.isNotBlank()) {
                                        pushStyle(SpanStyle(color = Accent))
                                        append(line.toolArgs)
                                        pop()
                                    }
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (duration.isNotBlank()) {
                                Text(
                                    (if (line.streaming) "Elapsed " else "Took ") + duration,
                                    color = colors.toolMeta,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        if (line.toolMeta.isNotBlank()) {
                            Text(
                                line.toolMeta,
                                color = if (line.toolIsError) Danger else colors.toolMeta,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (renderedOutput.isNotBlank()) {
                            val styledOutput = buildAnnotatedString {
                                renderedOutput.lines().forEachIndexed { index, outputLine ->
                                    val color = when {
                                        outputLine.startsWith("+") && !outputLine.startsWith("+++") -> colors.toolDiffAdded
                                        outputLine.startsWith("-") && !outputLine.startsWith("---") -> colors.toolDiffRemoved
                                        else -> colors.toolOutput
                                    }
                                    pushStyle(SpanStyle(color = color))
                                    append(outputLine)
                                    pop()
                                    if (index != renderedOutput.lines().lastIndex) append('\n')
                                }
                            }
                            Text(
                                styledOutput,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                            )
                        }
                        if (hiddenHint.isNotBlank()) {
                            if (line.collapsed) {
                                Text(
                                    hiddenHint,
                                    color = colors.toolMeta,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            TextButton(
                                onClick = { onToggleLine(lineIndex) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (line.collapsed) "Show all" else "Collapse",
                                    color = Blue,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
'''
text = replace_once(text, old_card, new_card, "tool card renderer")
# Stop applying the old global truncation rules to tool rows; the new renderer handles explicit previews itself.
text = replace_once(text,
'''            val hasHiddenToolContent = isTool && (fullText.lines().size > 10 || fullText.length > 240)\n            val visibleText = if (isTool && line.collapsed && fullText.length > 4_000) {\n                fullText.take(4_000) + "\\n…"\n            } else {\n                fullText\n            }''',
'''            val visibleText = fullText''',
"remove lossy generic tool truncation")
main.write_text(text)

# --- Version bump ---
gradle = Path("app/build.gradle.kts")
text = gradle.read_text()
if 'versionCode = 112' not in text or 'versionName = "5.19.13"' not in text:
    raise SystemExit("unexpected app version")
text = text.replace('versionCode = 112', 'versionCode = 113', 1)
text = text.replace('versionName = "5.19.13"', 'versionName = "5.19.14"', 1)
gradle.write_text(text)
