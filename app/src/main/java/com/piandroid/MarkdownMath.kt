package com.piandroid

import android.content.Context
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

internal data class InlineMath(val latex: String, val end: Int)

internal data class DisplayMath(val latex: String, val raw: String, val closed: Boolean, val nextLine: Int)

private val displayDelimiters = listOf("$$" to "$$", "\\[" to "\\]")

/**
 * A `$$ … $$` or `\[ … \]` block starting at [start]. While Pi is still streaming the closing
 * delimiter may not exist yet; the block then runs to the end and reports `closed = false`.
 * A line like `$$a$$ and more` is left to the paragraph's inline math.
 */
internal fun displayMathAt(lines: List<String>, start: Int): DisplayMath? {
    val first = lines[start].trim()
    val (open, close) = displayDelimiters.firstOrNull { first.startsWith(it.first) } ?: return null
    val rest = first.substring(open.length)
    val sameLine = rest.indexOf(close)
    if (sameLine >= 0) {
        val latex = rest.substring(0, sameLine)
        if (latex.isBlank() || rest.substring(sameLine + close.length).isNotBlank()) return null
        return DisplayMath(latex.trim(), first, true, start + 1)
    }
    val body = mutableListOf(rest)
    var index = start + 1
    while (index < lines.size) {
        val line = lines[index]
        val end = line.indexOf(close)
        if (end >= 0) {
            body += line.substring(0, end)
            val raw = lines.subList(start, index + 1).joinToString("\n")
            return DisplayMath(body.joinToString("\n").trim(), raw, true, index + 1)
        }
        body += line
        index++
    }
    return DisplayMath(body.joinToString("\n").trim(), lines.subList(start, lines.size).joinToString("\n"), false, lines.size)
}

/**
 * Inline math starting exactly at [from]: `$…$`, `$$…$$`, `\(…\)` or `\[…\]`.
 * Single dollars follow Pandoc's rule so prices stay text: the opening `$` must be followed by
 * a non-space, the closing `$` preceded by a non-space and not followed by a digit
 * ("$5 and $10" is not math). A span crossing inline code is not math either.
 */
internal fun findInlineMath(source: String, from: Int): InlineMath? {
    fun delimited(open: String, close: String): InlineMath? {
        if (!source.startsWith(open, from)) return null
        val end = source.indexOf(close, from + open.length)
        if (end < 0) return null
        val latex = source.substring(from + open.length, end)
        return if (latex.isBlank()) null else InlineMath(latex.trim(), end + close.length)
    }
    if (from > 0 && (source[from - 1] == '\\' || source[from - 1] == '$')) return null
    delimited("$$", "$$")?.let { return it }
    if (source.startsWith("$$", from)) return null
    delimited("\\(", "\\)")?.let { return it }
    delimited("\\[", "\\]")?.let { return it }
    if (source[from] != '$') return null
    val begin = from + 1
    if (begin >= source.length || source[begin].isWhitespace()) return null
    var end = source.indexOf('$', begin)
    while (end > 0 && source[end - 1] == '\\') end = source.indexOf('$', end + 1)
    if (end < 0 || source[end - 1].isWhitespace()) return null
    if (end + 1 < source.length && source[end + 1].isDigit()) return null
    if (source.substring(begin, end).contains('`')) return null
    return InlineMath(source.substring(begin, end), end + 1)
}

/** A typeset formula. Sizes are pixels; [depth] is the part below the baseline. */
internal class MathFormula private constructor(
    private val drawable: JLatexMathDrawable,
    val width: Float,
    val height: Float,
    val depth: Float,
    private val fontSizePx: Float
) {
    fun draw(scope: DrawScope, top: Float, left: Float = 0f) = scope.drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val save = native.save()
        native.translate(left, top)
        drawable.draw(native)
        native.restoreToCount(save)
    }

    /**
     * Inline placeholder whose vertical center sits on the text's center, padded so the formula's
     * baseline lands on the text baseline and the line grows evenly above and below.
     */
    fun inlineContent(density: Density): InlineTextContent {
        val metrics = Paint().apply { textSize = fontSizePx }.fontMetrics
        val center = -(metrics.ascent + metrics.descent) / 2f
        val ascent = height - depth
        val half = maxOf(ascent - center, depth + center)
        val top = half + center - ascent
        val placeholder = with(density) { Placeholder(width.toSp(), (half * 2).toSp(), PlaceholderVerticalAlign.TextCenter) }
        return InlineTextContent(placeholder) { Canvas(Modifier.fillMaxSize()) { draw(this, top) } }
    }

    companion object {
        private val cache = LruCache<String, Any>(256)
        private val failed = Any()
        @Volatile private var ready = false

        fun init(context: Context) {
            if (ready) return
            synchronized(this) {
                if (!ready) {
                    JLatexMathAndroid.init(context.applicationContext)
                    ready = true
                }
            }
        }

        fun of(latex: String, fontSizePx: Float, color: Color, display: Boolean): MathFormula? {
            if (!ready) return null
            val key = "$display|$fontSizePx|${color.toArgb()}|$latex"
            cache.get(key)?.let { return it as? MathFormula }
            val formula = runCatching {
                val drawable = JLatexMathDrawable.builder(if (display) latex else "\\textstyle $latex")
                    .textSize(fontSizePx)
                    .color(color.toArgb())
                    .build()
                val icon = drawable.icon()
                MathFormula(drawable, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat(), icon.iconDepth.toFloat(), fontSizePx)
            }.getOrNull()?.takeIf { it.width > 0f && it.height > 0f }
            cache.put(key, formula ?: failed)
            return formula
        }
    }
}

@Composable
internal fun MathBlockContent(latex: String, raw: String, closed: Boolean, colors: PiColors) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { 16.5.sp.toPx() }
    val formula = remember(latex, closed, fontSizePx, colors.markdownText) {
        if (closed) MathFormula.of(latex, fontSizePx, colors.markdownText, display = true) else null
    }
    if (formula == null) {
        Text(raw, color = if (closed) colors.markdownText else colors.markdownMuted, fontSize = 15.sp, lineHeight = 23.sp)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = width).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(with(density) { formula.width.toDp() }, with(density) { formula.height.toDp() })) { formula.draw(this, 0f) }
            }
        }
    }
}
