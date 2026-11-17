package com.kayx.manager.feature.filebrowser

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.ui.theme.Accent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Fraction of the row width the user has to drag before the item gets selected. */
private const val SWIPE_SELECT_FRACTION = 0.70f

/**
 * Drag a row sideways: the row itself follows the finger (a coloured "select" strip shows behind it). Once it has been
 * pulled 70 % of its width the item is selected ([onSwipe] fires once per gesture); the row then springs back.
 * Releasing earlier just springs back without selecting. Vertical scrolling and taps are unaffected.
 */
internal fun Modifier.swipeToSelect(enabled: Boolean, onSwipe: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onSwipe)
    val scope = rememberCoroutineScope()
    var dragX by remember { mutableFloatStateOf(0f) }
    val accent = Accent
    val density = LocalDensity.current.density

    this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var fired = false
            var back: Job? = null
            fun release() {
                back?.cancel()
                back = scope.launch {
                    animate(dragX, 0f, animationSpec = spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> dragX = v }
                }
            }
            detectHorizontalDragGestures(
                onDragStart = { back?.cancel(); fired = false },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    val w = size.width.toFloat()
                    dragX = (dragX + dx).coerceIn(-w, w)
                    if (!fired && abs(dragX) >= w * SWIPE_SELECT_FRACTION) { fired = true; current() }
                },
                onDragEnd = { release() },
                onDragCancel = { release() }
            )
        }
        .drawBehind {
            val x = dragX
            if (x == 0f) return@drawBehind
            val progress = (abs(x) / (size.width * SWIPE_SELECT_FRACTION)).coerceIn(0f, 1f)
            val r = 11f * density
            val left = if (x > 0) 0f else size.width + x
            drawRoundRect(accent.copy(alpha = .16f + .34f * progress), Offset(left, 0f), Size(abs(x), size.height), CornerRadius(r))
            // check mark that fades/grows in the exposed strip
            val cx = if (x > 0) minOf(abs(x) / 2f, 30f * density) else size.width - minOf(abs(x) / 2f, 30f * density)
            val cy = size.height / 2f
            val rr = (7f + 5f * progress) * density
            val p = Path().apply {
                moveTo(cx - rr * .55f, cy); lineTo(cx - rr * .1f, cy + rr * .45f); lineTo(cx + rr * .6f, cy - rr * .45f)
            }
            drawPath(p, Color.White.copy(alpha = progress), style = Stroke(width = 2.6f * density))
        }
        .graphicsLayer { translationX = dragX }
}

/**
 * Pull-down gesture host for a list.
 *  - dual pane: a gentle pull selects this pane (makes it the active one), a longer pull also refreshes;
 *  - single pane ([dual] = false): only the long pull exists and it refreshes.
 * The list follows the finger; the action fires when the finger is lifted.
 */
@Composable
internal fun PullHost(
    dual: Boolean,
    onSelectPane: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val selectPx = with(density) { 36.dp.toPx() }
    val refreshPx = with(density) { (if (dual) 110 else 84).dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val selectCb by rememberUpdatedState(onSelectPane)
    val refreshCb by rememberUpdatedState(onRefresh)

    fun stageOf(p: Float) = when { p >= refreshPx -> 2; dual && p >= selectPx -> 1; else -> 0 }
    fun setPull(v: Float) {
        pull = v.coerceIn(0f, refreshPx * 1.5f)
        val st = stageOf(pull)
        if (st != stage) { if (st > stage) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); stage = st }
    }
    fun release() {
        when (stageOf(pull)) {
            2 -> { if (dual) selectCb(); refreshCb() }
            1 -> selectCb()
        }
        scope.launch {
            animate(pull, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { v, _ -> pull = v }
            stage = 0
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull > 0f && available.y < 0f) {
                    val d = maxOf(available.y, -pull)
                    setPull(pull + d)
                    return Offset(0f, d)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only a real finger drag at the top of the list (not the tail of a fling) starts a pull.
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    setPull(pull + available.y * 0.55f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val had = pull > 0f
                if (had) release()
                return if (had) available else Velocity.Zero
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull }) { content() }
        if (pull > 0f) {
            val ready = stage > 0
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                    .graphicsLayer { alpha = (pull / selectPx).coerceIn(0f, 1f) }
                    .background(if (ready) Accent else Accent.copy(alpha = .45f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (stage) { 2 -> Icons.Default.Refresh; 1 -> Icons.Default.CheckCircle; else -> Icons.Default.ViewColumn },
                    null, Modifier.size(15.dp), tint = Color.White
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        stage == 2 -> "Release to refresh"
                        stage == 1 -> "Release to select panel"
                        dual -> "Keep pulling…"
                        else -> "Pull to refresh"
                    },
                    color = Color.White, fontSize = 12.sp
                )
            }
        }
    }
}
