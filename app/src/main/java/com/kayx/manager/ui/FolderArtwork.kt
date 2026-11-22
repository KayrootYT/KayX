package com.kayx.manager.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kayx.manager.R
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.theme.LocalPalette

/** Theme-aware 3D folder artwork extracted from the bundled resource pack. */
@Composable
fun FolderArtwork(
    path: String,
    modified: Long,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val palette = LocalPalette.current
    val kind = rememberFolderKind(path, modified, SettingsStore.folderIcons)
    val theme = if (palette.amoled) "amoled" else if (palette.dark) "dark" else "light"
    val drawable = when (kind) {
        FolderKind.PHOTO -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_image
            "dark" -> R.drawable.folder3d_dark_image
            else -> R.drawable.folder3d_light_image
        }
        FolderKind.MUSIC -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_music
            "dark" -> R.drawable.folder3d_dark_music
            else -> R.drawable.folder3d_light_music
        }
        FolderKind.VIDEO -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_video
            "dark" -> R.drawable.folder3d_dark_video
            else -> R.drawable.folder3d_light_video
        }
        FolderKind.PDF -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_pdf
            "dark" -> R.drawable.folder3d_dark_pdf
            else -> R.drawable.folder3d_light_pdf
        }
        FolderKind.SPREADSHEET -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_spreadsheet
            "dark" -> R.drawable.folder3d_dark_spreadsheet
            else -> R.drawable.folder3d_light_spreadsheet
        }
        FolderKind.PRESENTATION -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_presentation
            "dark" -> R.drawable.folder3d_dark_presentation
            else -> R.drawable.folder3d_light_presentation
        }
        FolderKind.TEXT -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_text
            "dark" -> R.drawable.folder3d_dark_text
            else -> R.drawable.folder3d_light_text
        }
        FolderKind.ARCHIVE -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_archive
            "dark" -> R.drawable.folder3d_dark_archive
            else -> R.drawable.folder3d_light_archive
        }
        FolderKind.APK -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_apk
            "dark" -> R.drawable.folder3d_dark_apk
            else -> R.drawable.folder3d_light_apk
        }
        FolderKind.CODE -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_code
            "dark" -> R.drawable.folder3d_dark_code
            else -> R.drawable.folder3d_light_code
        }
        FolderKind.DOCS -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_document
            "dark" -> R.drawable.folder3d_dark_document
            else -> R.drawable.folder3d_light_document
        }
        FolderKind.MIXED -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_all_types_mixed
            "dark" -> R.drawable.folder3d_dark_all_types_mixed
            else -> R.drawable.folder3d_light_all_types_mixed
        }
        FolderKind.EMPTY, FolderKind.NONE -> when (theme) {
            "amoled" -> R.drawable.folder3d_amoled_empty
            "dark" -> R.drawable.folder3d_dark_empty
            else -> R.drawable.folder3d_light_empty
        }
    }
    Image(
        painter = painterResource(drawable),
        contentDescription = "Folder",
        modifier = modifier.then(Modifier),
        contentScale = ContentScale.Fit
    )
}
