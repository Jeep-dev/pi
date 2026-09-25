package com.piandroid

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val CardShape = RoundedCornerShape(16.dp)
internal val PillShape = RoundedCornerShape(50)

/** Material roles derived from the Pi palette so stock components (dialogs, fields, switches) match. */
internal fun piColorScheme(colors: PiColors): ColorScheme = if (colors.isLight) {
    lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.blue,
        background = colors.bg,
        onBackground = colors.textMain,
        surface = colors.bg,
        onSurface = colors.textMain,
        surfaceVariant = colors.cardBg,
        onSurfaceVariant = colors.textMuted,
        surfaceContainer = colors.panelBg,
        surfaceContainerHigh = colors.panelBg,
        surfaceContainerHighest = colors.cardBg,
        outline = colors.border,
        outlineVariant = colors.border,
        error = colors.danger
    )
} else {
    darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.blue,
        background = colors.bg,
        onBackground = colors.textMain,
        surface = colors.bg,
        onSurface = colors.textMain,
        surfaceVariant = colors.cardBg,
        onSurfaceVariant = colors.textMuted,
        surfaceContainer = colors.panelBg,
        surfaceContainerHigh = colors.panelBg,
        surfaceContainerHighest = colors.cardBg,
        outline = colors.border,
        outlineVariant = colors.border,
        error = colors.danger
    )
}

@Composable
internal fun piFieldColors(): TextFieldColors {
    val colors = LocalPiColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textMain,
        unfocusedTextColor = colors.textMain,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.border,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accent,
        focusedPlaceholderColor = colors.textMuted,
        unfocusedPlaceholderColor = colors.textMuted,
        focusedContainerColor = colors.cardBg.copy(alpha = 0.35f),
        unfocusedContainerColor = colors.cardBg.copy(alpha = 0.35f)
    )
}

@Composable
internal fun PiLogo(size: Dp = 32.dp) {
    val colors = LocalPiColors.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(colors.textMain),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_pi_foreground),
            contentDescription = "Pi",
            colorFilter = ColorFilter.tint(colors.bg),
            modifier = Modifier.size(size * 1.25f)
        )
    }
}

@Composable
internal fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun PiIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = LocalPiColors.current.textMain, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else LocalPiColors.current.disabledAction)
    }
}

@Composable
internal fun PiCard(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPiColors.current
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(colors.panelBg).border(1.dp, colors.border, CardShape).padding(contentPadding),
        content = content
    )
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = LocalPiColors.current.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp)
    )
}

@Composable
internal fun PiChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, monospace: Boolean = false, leading: (@Composable RowScope.() -> Unit)? = null) {
    val colors = LocalPiColors.current
    Row(
        modifier
            .clip(PillShape)
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.cardBg)
            .border(1.dp, if (selected) colors.accent else Color.Transparent, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        leading?.invoke(this)
        Text(
            text,
            color = if (selected) colors.accent else colors.textMain,
            fontSize = 13.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PiPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val colors = LocalPiColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) colors.stopButtonBg else colors.accent,
            contentColor = if (danger) colors.textMain else colors.onAccent,
            disabledContainerColor = colors.disabledAction,
            disabledContentColor = colors.textMuted
        )
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
internal fun PanelHeader(title: String, onBack: () -> Unit, subtitle: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalPiColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.headerBg).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PiIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = colors.textMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
internal fun SettingRow(title: String, subtitle: String = "", onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalPiColors.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, color = colors.textMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPiColors.current.border))
}
