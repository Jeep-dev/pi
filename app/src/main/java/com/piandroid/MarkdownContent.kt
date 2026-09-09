package com.piandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MdText = Color(0xFFD8D6E3)
private val MdMuted = Color(0xFF9491A3)
private val MdAccent = Color(0xFFC5A3FF)
private val MdCyan = Color(0xFF63D1D1)
private val MdBorder = Color(0xFF77738E)
private val MdCodeBg = Color(0xFF171620)

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private fun cells(line: String): List<String> = line.trim().trim('|').split('|').map(String::trim)
private fun isTableRule(line: String): Boolean {
    val parts = cells(line)
    return parts.isNotEmpty() && parts.all { it.matches(Regex(":?-{3,}:?")) }
}

private fun parseMarkdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) { index++; continue }
        if (line.trimStart().startsWith("```")) {
            val language = line.trim().removePrefix("```").trim()
            index++
            val body = mutableListOf<String>()
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) body += lines[index++]
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(language, body.joinToString("\n"))
            continue
        }
        val heading = Regex("^(#{1,6})\\s+(.+)$").find(line)
        if (heading != null) {
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            index++
            continue
        }
        if (index + 1 < lines.size && line.contains('|') && isTableRule(lines[index + 1])) {
            val rows = mutableListOf(cells(line))
            index += 2
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) rows += cells(lines[index++])
            blocks += MarkdownBlock.Table(rows)
            continue
        }
        if (line.trim().matches(Regex("(-{3,}|_{3,}|\\*{3,})"))) {
            blocks += MarkdownBlock.Rule
            index++
            continue
        }
        val unordered = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
        val ordered = Regex("^\\s*\\d+[.)]\\s+(.+)$").find(line)
        if (unordered != null || ordered != null) {
            val isOrdered = ordered != null
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val match = if (isOrdered) Regex("^\\s*\\d+[.)]\\s+(.+)$").find(lines[index]) else Regex("^\\s*[-*+]\\s+(.+)$").find(lines[index])
                if (match == null) break
                items += match.groupValues[1]
                index++
            }
            blocks += MarkdownBlock.ListBlock(isOrdered, items)
            continue
        }
        if (line.trimStart().startsWith('>')) {
            val quote = mutableListOf<String>()
            while (index < lines.size && lines[index].trimStart().startsWith('>')) quote += lines[index++].trimStart().removePrefix(">").trimStart()
            blocks += MarkdownBlock.Quote(quote.joinToString("\n"))
            continue
        }
        val paragraph = mutableListOf(line)
        index++
        while (index < lines.size && lines[index].isNotBlank() &&
            !lines[index].trimStart().startsWith("```") &&
            !lines[index].matches(Regex("^(#{1,6})\\s+.*$")) &&
            !lines[index].matches(Regex("^\\s*[-*+]\\s+.*$")) &&
            !lines[index].matches(Regex("^\\s*\\d+[.)]\\s+.*$")) &&
            !(index + 1 < lines.size && lines[index].contains('|') && isTableRule(lines[index + 1]))) {
            paragraph += lines[index++]
        }
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return blocks
}

private fun inlineMarkdown(source: String) = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("**", index) -> {
                val end = source.indexOf("**", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFF0EEF7)))
                    append(source.substring(index + 2, end)); pop(); index = end + 2
                } else { append("**"); index += 2 }
            }
            source[index] == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(color = MdCyan, background = Color(0xFF252432), fontFamily = FontFamily.Monospace))
                    append(source.substring(index + 1, end)); pop(); index = end + 1
                } else { append('`'); index++ }
            }
            source[index] == '[' -> {
                val close = source.indexOf(']', index + 1)
                val openUrl = if (close >= 0 && close + 1 < source.length && source[close + 1] == '(') close + 1 else -1
                val closeUrl = if (openUrl >= 0) source.indexOf(')', openUrl + 1) else -1
                if (closeUrl > openUrl) {
                    pushStyle(SpanStyle(color = MdCyan)); append(source.substring(index + 1, close)); pop()
                    pushStyle(SpanStyle(color = MdMuted)); append(" (${source.substring(openUrl + 1, closeUrl)})"); pop()
                    index = closeUrl + 1
                } else { append(source[index]); index++ }
            }
            else -> { append(source[index]); index++ }
        }
    }
}

@Composable
fun PiMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text), color = MdText, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp)
                is MarkdownBlock.Heading -> Text(inlineMarkdown(block.text), color = MdAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = if (block.level <= 2) 16.sp else 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 3.dp))
                is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(MdCodeBg).border(1.dp, MdBorder).padding(9.dp)) {
                    if (block.language.isNotBlank()) Text(block.language, color = MdAccent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    Text(block.text, color = Color(0xFFC9E6E2), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
                is MarkdownBlock.Table -> MarkdownTable(block.rows)
                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { i, item -> Row {
                        Text(if (block.ordered) "${i + 1}. " else "• ", color = MdCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(inlineMarkdown(item), color = MdText, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
                    } }
                }
                is MarkdownBlock.Quote -> Box(Modifier.fillMaxWidth().background(Color(0xFF1C1B27)).padding(start = 10.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)) {
                    Text(inlineMarkdown(block.text), color = MdMuted, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, lineHeight = 20.sp)
                }
                MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MdBorder).padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    val widths: List<Dp> = (0 until columns).map { column ->
        val chars = rows.maxOf { it.getOrNull(column)?.length ?: 0 }.coerceIn(8, 32)
        (chars * 7 + 18).dp
    }
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, MdBorder)) {
        rows.forEachIndexed { rowIndex, row ->
            Row {
                for (column in 0 until columns) {
                    Text(
                        inlineMarkdown(row.getOrNull(column).orEmpty()),
                        color = if (rowIndex == 0) MdAccent else MdText,
                        fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.width(widths[column]).border(0.5.dp, MdBorder).padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
