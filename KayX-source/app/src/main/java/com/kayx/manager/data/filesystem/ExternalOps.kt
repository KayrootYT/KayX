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

    fun openWith(ctx: Context, f: File): Boolean {
        if (!f.isFile || !f.canRead()) { toast(ctx, "File is not readable"); return false }
        val uri = safeUriFor(ctx, f) ?: return false
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Android opens the default viewer, or presents a chooser when multiple viewers exist.
        return start(ctx, i)
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
        start(ctx, Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true))
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
