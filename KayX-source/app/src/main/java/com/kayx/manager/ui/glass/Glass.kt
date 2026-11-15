package com.kayx.manager.ui.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.theme.LocalPalette

/** Theme-aware backdrop. Dark: luminous liquid glow. Light: soft tint. AMOLED: pure black. */
@Composable
fun Modifier.glassBackground(): Modifier {
    val p = LocalPalette.current
    val glow = SettingsStore.glowBackground
    val accent = p.accent
    val accent2 = p.accent2
    return this.background(p.bg).drawBehind {
        if (p.amoled || !glow) return@drawBehind
        if (p.dark) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF0B1424), p.bg, Color(0xFF101027))))
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .24f), Color.Transparent),
                center = Offset(size.width * .04f, size.height * .08f), radius = size.width * .92f))
            drawRect(Brush.radialGradient(listOf(accent2.copy(alpha = .19f), Color.Transparent),
                center = Offset(size.width * .98f, size.height * .68f), radius = size.width * .82f))
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .035f), Color.Transparent),
                center = Offset(size.width * .52f, size.height * .42f), radius = size.width * .7f))
        } else {
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .14f), Color.Transparent),
                center = Offset(size.width * .04f, size.height * .06f), radius = size.width * .9f))
            drawRect(Brush.radialGradient(listOf(accent2.copy(alpha = .10f), Color.Transparent),
                center = Offset(size.width * .98f, size.height * .70f), radius = size.width * .8f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    tint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val p = LocalPalette.current
    // Callers pass the theme foreground (or nothing) for the neutral state and the accent when selected.
    val custom = tint != Color.Unspecified && tint != p.fg
    val fill: Brush = remember(custom, tint, p) { when {
        custom -> Brush.verticalGradient(listOf(tint.copy(alpha = .22f), tint.copy(alpha = .10f)))
        p.amoled -> SolidColor(p.groupFill)
        p.dark -> Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .155f), Color(0xFF17243A).copy(alpha = .20f), Color.White.copy(alpha = .055f)))
        else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .82f)))
    } }
    val edge: Brush = remember(p) { when {
        p.amoled -> Brush.linearGradient(listOf(Color.White.copy(alpha = .14f), Color.White.copy(alpha = .06f)))
        p.dark -> Brush.linearGradient(listOf(
            Color.White.copy(alpha = .42f), p.accent.copy(alpha = .13f),
            Color.White.copy(alpha = .055f), p.accent2.copy(alpha = .20f)))
        else -> Brush.linearGradient(listOf(Color.Black.copy(alpha = .09f), Color.Black.copy(alpha = .04f)))
    } }
    // background(shape)+border avoid the offscreen layer that clip() creates for every card (big scroll-lag source in long lists).
    val base = modifier.background(fill, shape).border(1.dp, edge, shape)
    val m = if (onClick != null || onLongClick != null)
        base.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    else base
    Box(m, content = content)
}
