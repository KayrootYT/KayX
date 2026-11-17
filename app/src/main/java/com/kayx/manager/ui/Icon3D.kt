package com.kayx.manager.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A "3D" tile: dark extruded base (thickness), lit gradient face, glossy top highlight and a bright rim.
 * [modifier] must already carry the size. Content is centred on the visible face.
 */
@Composable
fun Tile3D(
    modifier: Modifier,
    color: Color,
    corner: Dp = 11.dp,
    rotationY: Float = 0f,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current.density
    Box(
        modifier
            .graphicsLayer { this.rotationY = rotationY; cameraDistance = 14f * density }
            .drawBehind {
                val r = corner.toPx()
                val depth = size.height * .075f
                val faceH = size.height - depth
                val top = lerp(color, Color.White, .34f)
                val bottom = lerp(color, Color.Black, .22f)
                // extrusion / thickness
                drawRoundRect(lerp(color, Color.Black, .55f), Offset(0f, depth), Size(size.width, faceH), CornerRadius(r))
                // lit face
                drawRoundRect(Brush.verticalGradient(listOf(top, color, bottom), 0f, faceH), Offset.Zero, Size(size.width, faceH), CornerRadius(r))
                // gloss
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .42f), Color.Transparent), 0f, faceH * .56f),
                    Offset(size.width * .04f, faceH * .04f), Size(size.width * .92f, faceH * .52f), CornerRadius(r * .85f)
                )
                // rim light
                drawRoundRect(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = .55f), Color.White.copy(alpha = .06f)), Offset.Zero, Offset(size.width, faceH)),
                    Offset.Zero, Size(size.width, faceH), CornerRadius(r), style = Stroke(size.minDimension * .025f)
                )
            }
            .padding(bottom = 3.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Icon on a [Tile3D] with a drop-shadow under the glyph. [flip] spins the tile with a spring (used for selection). */
@Composable
fun Icon3D(icon: ImageVector, color: Color, size: Dp = 40.dp, flip: Boolean = false, contentDescription: String? = null) {
    val rot by animateFloatAsState(
        if (flip) 360f else 0f,
        spring(dampingRatio = .62f, stiffness = Spring.StiffnessLow), label = "icon3d-flip"
    )
    Tile3D(Modifier.size(size), color, corner = size * .26f, rotationY = rot) {
        val g = size * .52f
        Icon(icon, null, Modifier.size(g).offset(0.dp, 1.5.dp), tint = Color.Black.copy(alpha = .28f))
        Icon(icon, contentDescription, Modifier.size(g), tint = Color.White)
    }
}
