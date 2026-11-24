package com.kayx.manager.data.apk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

data class ApkBundleInspection(
    val type: String,
    val apkFiles: List<String>,
    val manifestFiles: List<String>,
    val unsupportedEntries: List<String>
)

object ApkBundleRepository {
    const val INSTALL_RESULT_ACTION = "com.kayx.manager.action.INSTALL_STATUS"

    private const val MAX_APK_COUNT = 64
    private const val MAX_TOTAL_APK_BYTES = 1L shl 30

    fun inspect(bundle: File): Result<ApkBundleInspection> = runCatching {
        require(bundle.isFile) { "Bundle is missing" }
        ZipFile(bundle).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            require(entries.size <= 4096) { "Bundle contains too many entries" }
            val names = entries.map { it.name }
            require(names.distinct().size == names.size) { "Bundle contains duplicate entry names" }
            names.forEach { name ->
                require(name.isNotBlank() && !name.startsWith("/") && !name.startsWith("\\") &&
                    !name.split('/', '\\').any { it == ".." } && name.none { it.code == 0 }) {
                    "Bundle contains an unsafe entry name"
                }
            }
            val apkEntries = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
            require(apkEntries.isNotEmpty()) { "No APK payloads found in bundle" }
            require(apkEntries.size <= MAX_APK_COUNT) { "Bundle contains too many APK files" }
            apkEntries.fold(0L) { total, entry ->
                require(entry.size > 0L) { "Bundle contains an empty or unknown-size APK payload" }
                require(entry.size <= MAX_TOTAL_APK_BYTES - total) {
                    "APK payload is larger than the supported 1 GiB limit"
                }
                total + entry.size
            }

            val apkFiles = apkEntries.map { it.name }.sortedWith(
                compareBy<String> { if (it.substringAfterLast('/').let { base -> base.equals("base.apk", true) || base.equals("base-master.apk", true) }) 0 else 1 }
                    .thenBy { it.lowercase() }
            )
            val manifestFiles = names.filter {
                it.equals("manifest.json", ignoreCase = true) ||
                    it.endsWith("/manifest.json", ignoreCase = true)
            }
            val type = if (bundle.extension.equals("apks", true) || apkFiles.any {
                it.substringBeforeLast('/', "").isNotEmpty()
            }) "APKS/XAPK" else "ZIP/APK bundle"
            ApkBundleInspection(type, apkFiles, manifestFiles, names.filterNot {
                it.endsWith(".apk", ignoreCase = true) || it.equals("manifest.json", true) ||
                    it.endsWith("/manifest.json", true)
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
            var totalWritten = 0L
            ZipFile(bundle).use { zip ->
                val baseName = inspection.apkFiles.firstOrNull {
                    it.substringAfterLast('/').let { base ->
                        base.equals("base.apk", ignoreCase = true) || base.equals("base-master.apk", ignoreCase = true)
                    }
                } ?: error("This bundle has no base.apk or base-master.apk; install requires a base APK")
                val ordered = listOf(baseName) + inspection.apkFiles.filterNot { it == baseName }
                val writtenNames = mutableSetOf<String>()
                ordered.forEachIndexed { index, name ->
                    val entry = zip.getEntry(name) ?: error("Missing APK payload: $name")
                    val outName = if (index == 0) "base.apk" else {
                        val splitName = name.substringAfterLast('/').substringBeforeLast('.')
                            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                            .take(80).ifBlank { "split_$index" }
                        "split_$splitName.apk"
                    }
                    check(writtenNames.add(outName)) { "Bundle contains duplicate split APK names" }
                    val remainingBytes = MAX_TOTAL_APK_BYTES - totalWritten
                    check(entry.size > 0L && entry.size <= remainingBytes) {
                        "APK payload exceeds the supported 1 GiB installation limit"
                    }
                    session.openWrite(outName, 0, entry.size).use { output ->
                        totalWritten += copyApkPayload(zip.getInputStream(entry), output, entry.size, remainingBytes)
                        session.fsync(output)
                    }
                }
            }

            // Close the write session before commit so all payloads are flushed and sealed.
            session.close()
            val intent = Intent(INSTALL_RESULT_ACTION).setClass(ctx, ApkInstallStatusReceiver::class.java)
            // PackageInstaller fills status extras into this callback Intent; MUTABLE is required.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
            sessionId
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Copies one declared ZIP APK entry while enforcing both the entry's declared length
     * and the remaining aggregate install budget. ZIP metadata is not trusted as the
     * actual number of decompressed bytes.
     */
    internal fun copyApkPayload(
        input: InputStream,
        output: OutputStream,
        declaredSize: Long,
        remainingBudget: Long
    ): Long {
        require(declaredSize > 0L) { "APK payload is empty or has an invalid size" }
        require(remainingBudget > 0L && declaredSize <= remainingBudget) {
            "APK payload exceeds the supported installation size limit"
        }
        input.use { source ->
            val buffer = ByteArray(32 * 1024)
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(count.toLong() <= remainingBudget - copied &&
                    count.toLong() <= declaredSize - copied) {
                    "APK payload expanded beyond its declared or allowed size"
                }
                output.write(buffer, 0, count)
                copied += count
            }
            require(copied == declaredSize) { "APK payload size does not match its ZIP metadata" }
            return copied
        }
    }
}
