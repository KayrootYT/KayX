package com.kayx.manager.feature.filebrowser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg

/** Highlights every case-insensitive occurrence of the active filename query. */
@Composable
internal fun HighlightedFileName(name: String, query: String, color: Color = Fg, maxLines: Int = 1) {
    val q = query.trim()
    val annotated = buildAnnotatedString {
        if (q.isEmpty()) append(name) else {
            var cursor = 0
            while (cursor < name.length) {
                val hit = name.indexOf(q, cursor, ignoreCase = true)
                if (hit < 0) { append(name.substring(cursor)); break }
                if (hit > cursor) append(name.substring(cursor, hit))
                withStyle(SpanStyle(color = Accent, background = Accent.copy(alpha = .22f))) {
                    append(name.substring(hit, hit + q.length))
                }
                cursor = hit + q.length
            }
        }
    }
    Text(annotated, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium, color = color)
}
