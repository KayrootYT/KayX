package com.kayx.manager.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Real preview for images, videos, APKs, PDFs and audio covers; a colored type badge for everything else. */
object Thumbs {
    private const val PX = 160
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, v: Bitmap) = v.byteCount
    }
    private val gate = Semaphore(3)
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "m4v", "ts")
    private val AUD = setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav")

    fun hasPreview(ext: String) = ext in IMG || ext in VID || ext == "apk" || ext == "pdf" || ext in AUD

    suspend fun load(ctx: Context, path: String, ext: String): Bitmap? {
        val key = path + File(path).lastModified()
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            gate.withPermit { runCatching { make(ctx, path, ext) }.getOrNull() }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun make(ctx: Context, path: String, ext: String): Bitmap? = when {
        ext in IMG -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= PX && o.outHeight / (s * 2) >= PX) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }
        ext in VID -> MediaMetadataRetriever().run {
            try { setDataSource(path); getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, PX, (PX * it.height / it.width.coerceAtLeast(1)).coerceAtLeast(1), true) } }
            finally { release() }
        }
        ext in AUD -> MediaMetadataRetriever().run {
            try { setDataSource(path); embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
            finally { release() }
        }
        ext == "apk" -> {
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(path, 0)
            info?.applicationInfo?.let { ai ->
                ai.sourceDir = path; ai.publicSourceDir = path
                val d = ai.loadIcon(pm)
                val b = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, PX, PX); d.draw(Canvas(b)); b
            }
        }
        ext == "pdf" -> ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(0).use { p ->
                    val w = PX; val h = (PX * p.height / p.width.coerceAtLeast(1)).coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b
                }
            }
        }
        else -> null
    }

    fun badge(ext: String): Pair<Color, String> = when (ext) {
        "doc", "docx", "odt", "rtf" -> Color(0xFF2B6CD9) to "W"
        "xls", "xlsx", "ods", "csv" -> Color(0xFF1E9E5A) to "X"
        "ppt", "pptx", "odp" -> Color(0xFFE0612A) to "P"
        "pdf" -> Color(0xFFD64545) to "PDF"
        "txt", "log" -> Color(0xFF6B7488) to "TXT"
        "md" -> Color(0xFF5B6FD8) to "MD"
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "jar" -> Color(0xFFE0A030) to "ZIP"
        "apk", "apks", "xapk" -> Color(0xFF2ECC71) to "APK"
        "mp3", "m4a", "wav", "flac", "ogg", "aac", "opus", "amr" -> Color(0xFF5A5F6B) to "♪"
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> Color(0xFFEF5B6B) to "▶"
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Color(0xFF4A90F5) to "IMG"
        "json", "xml", "html", "js", "kt", "java", "py", "smali", "css", "sh", "yml", "yaml" -> Color(0xFF8A5CD6) to "</>"
        "ttf", "otf" -> Color(0xFFB0507A) to "Aa"
        "epub" -> Color(0xFF2E9FA8) to "EPUB"
        "vcf" -> Color(0xFF3D7BF0) to "VCF"
        else -> Color(0xFF7A8294) to (ext.take(4).uppercase().ifEmpty { "FILE" })
    }
}

@Composable
fun FileThumb(path: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ext = path.substringAfterLast('.', "").lowercase()
    val bmp by produceState<Bitmap?>(null, path) { value = if (Thumbs.hasPreview(ext)) Thumbs.load(ctx, path, ext) else null }
    val shape = RoundedCornerShape(9.dp)
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        val (c, label) = Thumbs.badge(ext)
        Box(modifier.size(size).clip(shape).background(c.copy(alpha = .20f)), Alignment.Center) {
            Text(label, color = c, fontWeight = FontWeight.Bold, fontSize = if (label.length > 2) 11.sp else 17.sp, maxLines = 1)
        }
    }
}
