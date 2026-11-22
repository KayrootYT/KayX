package com.kayx.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawImage
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import com.kayx.manager.R
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.data.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * Renders one cell from the supplied 13-icon x 3-theme sprite sheet.
 * The source artwork is kept as one optimized PNG to avoid dozens of individual drawables.
 */
@Composable
fun FolderArtwork(kind: FolderKind, size: Dp = 44.dp, contentDescription: String? = null) {
    val systemDark = isSystemInDarkTheme()
    val themeRow = when (SettingsStore.themeMode) {
        ThemeMode.LIGHT -> 0
        ThemeMode.DARK -> 1
        ThemeMode.AMOLED -> 2
        ThemeMode.SYSTEM -> if (systemDark) 1 else 0
    }
    val bitmap = remember { ImageBitmap.imageResource(R.drawable.folder3d_sheets) }
    Canvas(Modifier.size(size)) {
        val cell = 32
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(kind.spriteIndex * cell, themeRow * cell),
            srcSize = IntSize(cell, cell),
            dstSize = IntSize(size.width.toPx().roundToInt(), size.height.toPx().roundToInt()),
            filterQuality = FilterQuality.Medium
        )
    }
}
