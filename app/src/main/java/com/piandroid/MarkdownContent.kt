package com.piandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
    data class Math(val latex: String, val raw: String, val closed: Boolean) : MarkdownBlock
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
        val math = displayMathAt(lines, index)
        if (math != null) {
            blocks += MarkdownBlock.Math(math.latex, math.raw, math.closed)
            index = math.nextLine
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
            displayMathAt(lines, index) == null &&
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

private class InlineText(val text: AnnotatedString, val content: Map<String, InlineTextContent>)

private class InlineMarkdownBuilder(
    private val colors: PiColors,
    private val fontSizePx: Float,
    private val density: Density,
    private val mathColor: Color,
    private val maxWidthPx: Float
) {
    val builder = AnnotatedString.Builder()
    val content = mutableMapOf<String, InlineTextContent>()

    private fun appendMath(latex: String, raw: String, color: Color) {
        // A formula wider than the line is shrunk to fit; Compose cannot wrap a placeholder.
        val formula = MathFormula.of(latex, fontSizePx, color, display = false)?.let {
            if (it.width <= maxWidthPx) it else MathFormula.of(latex, fontSizePx * maxWidthPx / it.width * 0.98f, color, display = false)
        }
        if (formula == null) { builder.append(raw); return }
        val id = "math${content.size}"
        builder.appendInlineContent(id, raw.replace('\n', ' '))
        content[id] = formula.inlineContent(density)
    }

    fun append(source: String, color: Color = mathColor) {
        var index = 0
        while (index < source.length) {
            val math = findInlineMath(source, index)
            when {
                math != null -> {
                    appendMath(math.latex, source.substring(index, math.end), color)
                    index = math.end
                }
                source.startsWith("\\$", index) -> { builder.append('$'); index += 2 }
                source.startsWith("**", index) -> {
                    val end = source.indexOf("**", index + 2)
                    if (end > index + 2) {
                        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.markdownStrong))
                        append(source.substring(index + 2, end), colors.markdownStrong); builder.pop(); index = end + 2
                    } else { builder.append("**"); index += 2 }
                }
                source[index] == '`' -> {
                    val end = source.indexOf('`', index + 1)
                    if (end > index + 1) {
                        builder.pushStyle(SpanStyle(color = colors.markdownCyan, background = colors.markdownInlineCodeBg, fontFamily = FontFamily.Monospace))
                        builder.append(source.substring(index + 1, end)); builder.pop(); index = end + 1
                    } else { builder.append('`'); index++ }
                }
                source[index] == '[' -> {
                    val close = source.indexOf(']', index + 1)
                    val openUrl = if (close >= 0 && close + 1 < source.length && source[close + 1] == '(') close + 1 else -1
                    val closeUrl = if (openUrl >= 0) source.indexOf(')', openUrl + 1) else -1
                    if (closeUrl > openUrl) {
                        builder.pushStyle(SpanStyle(color = colors.markdownCyan)); builder.append(source.substring(index + 1, close)); builder.pop()
                        builder.pushStyle(SpanStyle(color = colors.markdownMuted)); builder.append(" (${source.substring(openUrl + 1, closeUrl)})"); builder.pop()
                        index = closeUrl + 1
                    } else { builder.append(source[index]); index++ }
                }
                else -> { builder.append(source[index]); index++ }
            }
        }
    }
}

private fun inlineMarkdown(source: String, colors: PiColors, fontSize: TextUnit, density: Density, color: Color, maxWidthPx: Float = Float.MAX_VALUE): InlineText {
    val inline = InlineMarkdownBuilder(colors, with(density) { fontSize.toPx() }, density, color, maxWidthPx)
    inline.append(source)
    return InlineText(inline.builder.toAnnotatedString(), inline.content)
}

@Composable
private fun MarkdownText(
    source: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null
) {
    val colors = LocalPiColors.current
    val density = LocalDensity.current
    // Shrink formulas wider than the screen. Not BoxWithConstraints: quotes and tables measure
    // their rows by intrinsic size, which a SubcomposeLayout cannot answer (it crashed the app).
    val maxWidthPx = with(density) { (LocalConfiguration.current.screenWidthDp.dp - 48.dp).toPx() }
    val inline = remember(source, colors, fontSize, density, color, maxWidthPx) { inlineMarkdown(source, colors, fontSize, density, color, maxWidthPx) }
    Text(inline.text, color = color, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight, inlineContent = inline.content, modifier = modifier)
}

@Composable
fun PiMarkdown(text: String, modifier: Modifier = Modifier) {
    val colors = LocalPiColors.current
    val context = LocalContext.current
    remember { MathFormula.init(context) }
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownText(block.text, color = colors.markdownText, fontSize = 15.sp, lineHeight = 23.sp)
                is MarkdownBlock.Math -> MathBlockContent(block.latex, block.raw, block.closed, colors)
                is MarkdownBlock.Heading -> MarkdownText(
                    block.text,
                    color = colors.markdownStrong,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = when (block.level) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp },
                    lineHeight = when (block.level) { 1 -> 28.sp; 2 -> 25.sp; else -> 23.sp },
                    modifier = Modifier.padding(top = 4.dp)
                )
                is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.markdownCodeBg).border(1.dp, colors.markdownBorder.copy(alpha = if (colors.isLight) 1f else 0.6f), RoundedCornerShape(14.dp))) {
                    if (block.language.isNotBlank()) Text(block.language, color = colors.markdownMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().background(colors.markdownInlineCodeBg.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp))
                    Text(block.text, color = colors.markdownCodeText, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp))
                }
                is MarkdownBlock.Table -> MarkdownTable(block.rows, colors)
                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEachIndexed { i, item -> Row {
                        Text(if (block.ordered) "${i + 1}." else "•", color = colors.markdownMuted, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.width(if (block.ordered) 24.dp else 16.dp))
                        MarkdownText(item, color = colors.markdownText, fontSize = 15.sp, lineHeight = 23.sp)
                    } }
                }
                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.markdownAccent.copy(alpha = 0.5f)))
                    MarkdownText(block.text, color = colors.markdownMuted, fontSize = 14.5.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
                }
                MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(colors.markdownBorder))
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, colors: PiColors) {
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    val widths: List<Dp> = (0 until columns).map { column ->
        val chars = rows.maxOf { it.getOrNull(column)?.length ?: 0 }.coerceIn(8, 32)
        (chars * 7 + 18).dp
    }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(10.dp)).border(1.dp, colors.markdownBorder, RoundedCornerShape(10.dp))) {
        rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                for (column in 0 until columns) {
                    Box(
                        modifier = Modifier
                            .width(widths[column])
                            .fillMaxHeight()
                            .background(if (rowIndex == 0) colors.markdownCodeBg else Color.Transparent)
                            .border(0.5.dp, colors.markdownBorder)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        MarkdownText(
                            row.getOrNull(column).orEmpty(),
                            color = if (rowIndex == 0) colors.markdownStrong else colors.markdownText,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
