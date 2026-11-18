package com.kayx.manager.data.filesystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ExternalOps {
    /** Null (instead of a crash) when the file lives in a location FileProvider cannot expose. */
    fun safeUriFor(ctx: Context, f: File): Uri? = try {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    } catch (_: Exception) {
        toast(ctx, "Cannot share or open files from this protected location")
        null
    }
    private val explicitMime = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword", "dot" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docm" to "application/vnd.ms-word.document.macroEnabled.12",
        "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "epub" to "application/epub+zip"
    )
    private val officePdfExtensions = explicitMime.keys
    fun isOfficeOrPdf(f: File): Boolean = f.isFile && f.extension.lowercase() in officePdfExtensions
    fun mime(f: File): String = explicitMime[f.extension.lowercase()]
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i); true
    } catch (e: Exception) {
        toast(ctx, e.message ?: "No app can handle this"); false
    }

    fun setAs(ctx: Context, f: File) {
        val uri = safeUriFor(ctx, f) ?: return
        val i = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, Intent.createChooser(i, "Set as"))
    }
    fun createRingtone(ctx: Context, f: File) {
        val uri = safeUriFor(ctx, f) ?: return
        val i = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, Intent.createChooser(i, "Create ringtone"))
    }
    /** Real print for PDFs via the system print dialog; other files open in a viewer. */
    fun print(ctx: Context, f: File) {
        if (f.extension.lowercase() != "pdf") { toast(ctx, "Opening viewer - use its Print option"); openWith(ctx, f); return }
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        pm.print(f.name, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(o: android.print.PrintAttributes?, n: android.print.PrintAttributes?, c: android.os.CancellationSignal?,
                                  cb: LayoutResultCallback, e: android.os.Bundle?) {
                cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder(f.name)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>?, d: android.os.ParcelFileDescriptor, c: android.os.CancellationSignal?, cb: WriteResultCallback) {
                try { f.inputStream().use { i -> java.io.FileOutputStream(d.fileDescriptor).use { o -> i.copyTo(o) } }
                    cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    }

    /** PDF -> PNG pages (saved in "<name>_pages" next to the file); images -> JPG/PNG swap; others open in a viewer. Returns a result message. */
    fun convert(ctx: Context, f: File): String {
        return try {
            when (f.extension.lowercase()) {
                "pdf" -> {
                    val out = File(f.parentFile, f.nameWithoutExtension + "_pages").apply { mkdirs() }
                    val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    android.graphics.pdf.PdfRenderer(pfd).use { r ->
                        for (i in 0 until r.pageCount) r.openPage(i).use { pg ->
                            val bmp = android.graphics.Bitmap.createBitmap(pg.width * 2, pg.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            File(out, "page_${i + 1}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                    "Saved ${out.listFiles()?.size ?: 0} PNG pages in ${out.name}"
                }
                "png", "jpg", "jpeg", "webp" -> {
                    val png = f.extension.lowercase() != "png"
                    val dst = File(f.parentFile, f.nameWithoutExtension + if (png) ".png" else ".jpg")
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.path) ?: return "Cannot decode image"
                    dst.outputStream().use { bmp.compress(if (png) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                    "Saved ${dst.name}"
                }
                else -> { openWith(ctx, f); "Opening in a converter-capable app (Office/WPS)" }
            }
        } catch (e: Exception) { "Convert failed: " + (e.message ?: "error") }
    }

    fun pinShortcut(ctx: Context, f: File) {
        val i = Intent(ctx, com.kayx.manager.MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("path", f.absolutePath)
        val s = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "kx_" + f.absolutePath.hashCode())
            .setShortLabel(f.name).setIntent(i)
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, ctx.applicationInfo.icon)).build()
        if (!androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, s, null)) toast(ctx, "Launcher does not support shortcuts")
    }

    fun openWith(ctx: Context, f: File): Boolean {
        if (!f.isFile || !f.canRead()) { toast(ctx, "File is not readable"); return false }
        val uri = safeUriFor(ctx, f) ?: return false
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolved = ctx.packageManager.queryIntentActivities(i, 0)
        if (resolved.isEmpty()) {
            toast(ctx, "No app is installed that can open ${mime(f)}")
            return false
        }
        return start(ctx, Intent.createChooser(i, "Open with"))
    }

    /** Open [f] in one specific installed app (used by the "Open with…" grid and by saved defaults). */
    fun openWithComponent(ctx: Context, f: File, pkg: String, cls: String, mimeHint: String? = null): Boolean {
        if (!f.isFile || !f.canRead()) { toast(ctx, "File is not readable"); return false }
        val uri = safeUriFor(ctx, f) ?: return false
        val m = mime(f).let { if (it == "application/octet-stream") (mimeHint ?: "*/*") else it }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).setClassName(pkg, cls)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return start(ctx, i)
    }

    /** Apps that can open files of [mime]; pass the wildcard type for every app that registers a VIEW handler. */
    fun appsFor(ctx: Context, mime: String): List<android.content.pm.ResolveInfo> {
        val i = Intent(Intent.ACTION_VIEW).setType(mime)
        return runCatching { ctx.packageManager.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY or android.content.pm.PackageManager.MATCH_ALL) }
            .getOrDefault(emptyList())
            .filter { it.activityInfo?.exported == true && it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .sortedBy { it.loadLabel(ctx.packageManager).toString().lowercase() }
    }

    fun share(ctx: Context, files: List<File>) {
        if (files.isEmpty()) { toast(ctx, "Select files (folders cannot be shared)"); return }
        val uris = files.map { safeUriFor(ctx, it) ?: return }
        val i = if (files.size == 1) Intent(Intent.ACTION_SEND).setType(mime(files[0])).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, Intent.createChooser(i, "Share"))
    }

    fun installApk(ctx: Context, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            toast(ctx, "Allow \"Install unknown apps\" for KayX, then try again")
            start(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
            return
        }
        if (!f.isFile || !f.extension.equals("apk", true) || !f.canRead()) { toast(ctx, "Invalid or unreadable APK file"); return }
        val uri = safeUriFor(ctx, f) ?: return
        val contentUri = uri
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, intent)
    }

    fun copyText(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("KayX", text))
    }

    fun uriName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    fun readUri(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
}
