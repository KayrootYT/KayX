package com.kayx.manager.data.apk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

data class ApkBundleInspection(
    val type: String,
    val apkFiles: List<String>,
    val manifestFiles: List<String>,
    val unsupportedEntries: List<String>
)

object ApkBundleRepository {
    const val INSTALL_RESULT_ACTION = "com.kayx.manager.action.INSTALL_STATUS"

    fun inspect(bundle: File): Result<ApkBundleInspection> = runCatching {
        require(bundle.isFile) { "Bundle is missing" }
        ZipFile(bundle).use { zip ->
            val names = zip.entries().toList().map { it.name }
            val apkFiles = names.filter { it.endsWith(".apk", ignoreCase = true) }
                .sortedWith(compareBy<String> { if (it.endsWith("base.apk", ignoreCase = true)) 0 else 1 }.thenBy { it })
            val manifestFiles = names.filter {
                it.equals("manifest.json", ignoreCase = true) ||
                    it.endsWith("/manifest.json", ignoreCase = true)
            }
            val supported = apkFiles.isNotEmpty()
            val type = if (bundle.extension.equals("apks", true) || names.any {
                it.endsWith(".apk", ignoreCase = true) && it.contains("/")
            }) "APKS/XAPK" else "ZIP/APK bundle"
            check(supported) { "No APK payloads found in bundle" }
            ApkBundleInspection(type, apkFiles, manifestFiles, names.filterNot {
                it.endsWith(".apk", ignoreCase = true) || it.contains("manifest")
            })
        }
    }

    fun install(ctx: Context, bundle: File): Result<Int> = runCatching {
        val inspection = inspect(bundle).getOrThrow()
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            ZipFile(bundle).use { zip ->
                for ((index, name) in inspection.apkFiles.withIndex()) {
                    val entry = zip.getEntry(name) ?: continue
                    zip.getInputStream(entry).use { input ->
                        val outName = "split-$index.apk"
                        session.openWrite(outName, 0, entry.size.coerceAtLeast(0)).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
            }
            val intent = Intent(INSTALL_RESULT_ACTION).setPackage(ctx.packageName)
            // PackageInstaller fills status extras into this callback Intent; MUTABLE is required.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
            sessionId
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        }
    }
}
