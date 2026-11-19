package com.kayx.manager.ui.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.settings.BgStyle
import com.kayx.manager.data.settings.SettingsStore
import kotlin.math.cos
import kotlin.math.sin
import com.kayx.manager.ui.theme.LocalPalette

/**
 * Theme-aware backdrop.
 * GLOW: static luminous glow. AURORA / ORBS / CUBES: slowly animated (seamless loop, drawn in the draw phase only,
 * so the animation never triggers recomposition). AMOLED keeps a black base; animated styles are drawn dimmer there.
 */
@Composable
fun Modifier.glassBackground(): Modifier {
    val p = LocalPalette.current
    val style = SettingsStore.bgStyle
    val animated = style == BgStyle.AURORA || style == BgStyle.ORBS || style == BgStyle.CUBES
    val phase: State<Float>? = if (animated) {
        rememberInfiniteTransition(label = "kayx-bg").animateFloat(
            0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "kayx-bg-phase"
        )
    } else null
    val accent = p.accent
    val accent2 = p.accent2
    val scratch = remember { Path() }
    return this.background(p.bg).drawBehind {
        if (style == BgStyle.OFF) return@drawBehind
        if (style == BgStyle.GLOW) {
            if (p.amoled) return@drawBehind
            drawStaticGlow(p.dark, p.bg, accent, accent2)
            return@drawBehind
        }
        // animated styles
        if (p.dark && !p.amoled) drawRect(Brush.linearGradient(listOf(Color(0xFF0B1424), p.bg, Color(0xFF101027))))
        val a = if (p.amoled) .62f else if (p.dark) 1f else .62f   // overall strength
        val t = phase?.value ?: 0f
        when (style) {
            BgStyle.AURORA -> drawAurora(t, accent, accent2, a)
            BgStyle.ORBS -> drawOrbs(t, accent, accent2, a, p.dark)
            else -> drawCubes(t, accent, accent2, a, scratch)
        }
    }
}

private fun DrawScope.drawStaticGlow(dark: Boolean, bg: Color, accent: Color, accent2: Color) {
    if (dark) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF0B1424), bg, Color(0xFF101027))))
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

private const val TAU = 6.2831855f

/** Three big soft colour fields drifting on Lissajous paths. */
private fun DrawScope.drawAurora(t: Float, c1: Color, c2: Color, a: Float) {
    val w = size.width; val h = size.height
    val c3 = lerp(c1, c2, .5f)
    fun blob(color: Color, fx: Int, fy: Int, ph: Float, bx: Float, by: Float, r: Float, alpha: Float) {
        val cx = w * (bx + .30f * sin(TAU * t * fx + ph))
        val cy = h * (by + .22f * cos(TAU * t * fy + ph * 1.7f))
        drawRect(Brush.radialGradient(listOf(color.copy(alpha = alpha * a), Color.Transparent), center = Offset(cx, cy), radius = w * r))
    }
    blob(c1, 1, 2, 0f, .30f, .25f, 1.0f, .34f)
    blob(c2, 2, 1, 2.1f, .75f, .60f, .95f, .28f)
    blob(c3, 1, 1, 4.2f, .50f, .85f, .85f, .24f)
}

private class OrbSpec(val bx: Float, val by: Float, val z: Float, val fx: Int, val fy: Int, val ph: Float, val useAccent2: Boolean)

private val ORBS = listOf(
    OrbSpec(.15f, .18f, .35f, 1, 2, 0.0f, false), OrbSpec(.82f, .12f, .60f, 2, 1, 1.3f, true),
    OrbSpec(.55f, .38f, .25f, 1, 1, 2.6f, false), OrbSpec(.20f, .62f, .85f, 1, 2, 3.9f, true),
    OrbSpec(.85f, .55f, .45f, 2, 2, 5.1f, false), OrbSpec(.45f, .82f, .70f, 1, 1, 0.7f, true),
    OrbSpec(.90f, .88f, .30f, 2, 1, 2.0f, false)
).sortedBy { it.z } // far (small z) first so near orbs overlap them

/** Glossy spheres with specular highlight + soft contact shadow; depth controls size, speed and opacity. */
private fun DrawScope.drawOrbs(t: Float, c1: Color, c2: Color, a: Float, dark: Boolean) {
    val w = size.width; val h = size.height
    for (o in ORBS) {
        val r = w * (.07f + .15f * o.z)
        val cx = w * (o.bx + .08f * (.4f + o.z) * sin(TAU * t * o.fx + o.ph))
        val cy = h * (o.by + .06f * (.4f + o.z) * cos(TAU * t * o.fy + o.ph * 1.3f))
        val base = if (o.useAccent2) c2 else c1
        val alpha = (.30f + .45f * o.z) * a
        // contact shadow
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = (if (dark) .30f else .14f) * alpha), Color.Transparent),
            center = Offset(cx + r * .25f, cy + r * .75f), radius = r * 1.15f), radius = r * 1.15f, center = Offset(cx + r * .25f, cy + r * .75f))
        // sphere body: highlight (upper-left) -> body colour -> dark rim
        val hl = Offset(cx - r * .38f, cy - r * .42f)
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = .75f * alpha),
                .28f to base.copy(alpha = alpha),
                .78f to base.copy(alpha = alpha * .55f),
                1f to Color.Black.copy(alpha = .35f * alpha),
                center = hl, radius = r * 1.55f
            ), radius = r, center = Offset(cx, cy)
        )
        // rim light on the lower-right edge
        drawCircle(base.copy(alpha = .35f * alpha), radius = r, center = Offset(cx, cy), style = Stroke(width = r * .03f))
    }
}

private class CubeSpec(val bx: Float, val by: Float, val size: Float, val mx: Int, val my: Int, val ph: Float, val rx: Int, val ry: Int, val useAccent2: Boolean)

private val CUBES = listOf(
    CubeSpec(.16f, .16f, .075f, 1, 2, 0.0f, 1, 2, false), CubeSpec(.84f, .22f, .055f, 2, 1, 1.5f, 2, 1, true),
    CubeSpec(.50f, .46f, .045f, 1, 1, 3.0f, 1, 1, false), CubeSpec(.14f, .70f, .060f, 1, 2, 4.5f, 2, 3, true),
    CubeSpec(.86f, .74f, .085f, 2, 2, 0.8f, 1, 2, false), CubeSpec(.52f, .90f, .050f, 1, 1, 2.2f, 3, 1, true)
)
private val FACE_U = intArrayOf(1, 2, 0)
private val FACE_V = intArrayOf(2, 0, 1)
private val CORNERS_U = floatArrayOf(1f, 1f, -1f, -1f)
private val CORNERS_V = floatArrayOf(1f, -1f, -1f, 1f)

/** Rotating shaded 3D cubes (real rotation + perspective projection, hidden faces culled, simple directional light). */
private fun DrawScope.drawCubes(t: Float, c1: Color, c2: Color, a: Float, path: Path) {
    val w = size.width; val h = size.height
    val v = FloatArray(3); val n = FloatArray(3); val px = FloatArray(4); val py = FloatArray(4)
    for (c in CUBES) {
        val cx = w * (c.bx + .05f * sin(TAU * t * c.mx + c.ph))
        val cy = h * (c.by + .04f * cos(TAU * t * c.my + c.ph * 1.4f))
        val s = w * c.size
        val ay = TAU * t * c.ry + c.ph; val ax = TAU * t * c.rx + c.ph * .6f
        val cyaw = cos(ay); val syaw = sin(ay); val cp = cos(ax); val sp = sin(ax)
        val base = if (c.useAccent2) c2 else c1
        // rotate (x,y,z) about Y then X into out[]
        fun rot(x: Float, y: Float, z: Float, out: FloatArray) {
            val x1 = x * cyaw + z * syaw; val z1 = -x * syaw + z * cyaw
            out[0] = x1; out[1] = y * cp - z1 * sp; out[2] = y * sp + z1 * cp
        }
        for (axis in 0..2) for (sg in intArrayOf(-1, 1)) {
            val nv = FloatArray(3).also { it[axis] = sg.toFloat() }
            rot(nv[0], nv[1], nv[2], n)
            if (n[2] >= 0f) continue // facing away from the camera (camera looks along +z)
            val u = FACE_U[axis]; val vv = FACE_V[axis]
            for (k in 0..3) {
                val q = FloatArray(3)
                q[axis] = sg.toFloat(); q[u] = CORNERS_U[k]; q[vv] = CORNERS_V[k]
                rot(q[0], q[1], q[2], v)
                val persp = 4.5f / (4.5f + v[2])
                px[k] = cx + s * v[0] * persp; py[k] = cy + s * v[1] * persp
            }
            val shade = (n[0] * -.42f + n[1] * -.52f + n[2] * -.74f).coerceIn(.12f, 1f)
            path.reset(); path.moveTo(px[0], py[0]); path.lineTo(px[1], py[1]); path.lineTo(px[2], py[2]); path.lineTo(px[3], py[3]); path.close()
            val lit = lerp(Color.Black, base, .35f + .65f * shade)
            drawPath(path, lit.copy(alpha = .62f * a))
            drawPath(path, Color.White.copy(alpha = .30f * a * shade), style = Stroke(width = 1.2f))
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, animationSpec = tween(150), label = "glass-card-press")
    val elevation by animateFloatAsState(if (pressed) 3f else 10f, animationSpec = tween(180), label = "glass-card-depth")
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
    // Translucent layered fill, fine specular edge and restrained physical lift.
    // Avoid per-row blur/offscreen layers so long file lists remain smooth.
    val base = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(elevation.dp, shape, clip = false)
        .background(fill, shape)
        .border(1.dp, edge, shape)
        .drawBehind {
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (p.dark) .16f else .72f), Color.Transparent),
                    startY = 0f, endY = size.height * .42f
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
            )
        }
    val m = if (onClick != null || onLongClick != null)
        base.combinedClickable(interactionSource = interaction, indication = null,
            onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    else base
    Box(m, content = content)
}
