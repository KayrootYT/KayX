package com.kayx.manager.data.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

data class ApkInfo(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val size: Long,
    val certSha256: String,
    val certSha1: String,
    val certSubject: String,
    val permissions: List<String>,
    val activities: List<ApkComponent>,
    val services: List<ApkComponent>,
    val receivers: List<ApkComponent>,
    val providers: List<ApkComponent>,
    val dexFiles: List<String>,
    val nativeLibraries: List<String>,
    val resourceCount: Int,
    val assetCount: Int,
    val certDetails: List<Pair<String, String>> = emptyList()
)

data class ApkComponent(
    val name: String,
    val exported: Boolean,
    val permission: String?,
    val enabled: Boolean
)

class ApkRepository(private val context: Context) {

    companion object {
        private const val MAX_APK_ARCHIVE_BYTES = 2L shl 30
        private const val MAX_APK_ZIP_ENTRIES = 100_000
    }

    fun getApkInfo(apkFile: File): Result<ApkInfo> = runCatching {
        require(apkFile.isFile && apkFile.canRead()) { "APK file is missing or unreadable" }
        require(apkFile.length() in 1..MAX_APK_ARCHIVE_BYTES) {
            "APK file is empty or exceeds the supported 2 GiB inspection limit"
        }

        val pm = context.packageManager
        val signingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or signingFlag

        val pkgInfo: PackageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("Failed to parse APK archive info")

        pkgInfo.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
        }

        val appLabel = pkgInfo.applicationInfo?.loadLabel(pm)?.toString() ?: apkFile.nameWithoutExtension
        val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pkgInfo.applicationInfo?.minSdkVersion ?: 0
        } else 0

        val targetSdk = pkgInfo.applicationInfo?.targetSdkVersion ?: 0

        var certSha256 = "N/A"
        var certSha1 = "N/A"
        var certSubject = "N/A"
        val certDetails = mutableListOf<Pair<String, String>>()

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }

        signatures?.firstOrNull()?.let { sig ->
            runCatching {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(sig.toByteArray().inputStream()) as X509Certificate
                certSubject = cert.subjectDN.name
                val bytes = cert.encoded
                certSha256 = hashBytes(bytes, "SHA-256")
                certSha1 = hashBytes(bytes, "SHA-1")
                certDetails += "Issuer" to cert.issuerDN.name
                certDetails += "Valid from" to cert.notBefore.toString()
                certDetails += "Valid until" to cert.notAfter.toString()
                certDetails += "Serial number (hex)" to cert.serialNumber.toString(16)
                certDetails += "Signature algorithm" to cert.sigAlgName
                val key = cert.publicKey
                certDetails += "Public key algorithm" to key.algorithm
                if (key is RSAPublicKey) {
                    certDetails += "Key size" to "${key.modulus.bitLength()} bits"
                    certDetails += "Public exponent" to key.publicExponent.toString()
                    certDetails += "Modulus (decimal)" to key.modulus.toString()
                }
            }
        }

        // Read the ZIP central directory once. Reopening and walking it separately for
        // each category multiplies work on large APKs and can amplify malformed archives.
        val entries = readApkEntries(apkFile) { true }

        ApkInfo(
            appName = appLabel,
            packageName = pkgInfo.packageName ?: "Unknown",
            versionName = pkgInfo.versionName ?: "1.0",
            versionCode = verCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            size = apkFile.length(),
            certSha256 = certSha256,
            certSha1 = certSha1,
            certSubject = certSubject,
            permissions = pkgInfo.requestedPermissions?.toList().orEmpty(),
            activities = pkgInfo.activities.orEmpty().map { ApkComponent(it.name, it.exported, it.permission, it.enabled) },
            services = pkgInfo.services.orEmpty().map { ApkComponent(it.name, it.exported, it.permission, it.enabled) },
            receivers = pkgInfo.receivers.orEmpty().map { ApkComponent(it.name, it.exported, it.permission, it.enabled) },
            providers = pkgInfo.providers.orEmpty().map { ApkComponent(it.name, it.exported, it.readPermission ?: it.writePermission, it.enabled) },
            dexFiles = entries.filter { it.matches(Regex("classes(\\d+)?\\.dex")) },
            nativeLibraries = entries.filter { it.startsWith("lib/") && it.endsWith(".so") },
            resourceCount = entries.count { it.startsWith("res/") },
            assetCount = entries.count { it.startsWith("assets/") },
            certDetails = certDetails
        )
    }

    private fun readApkEntries(file: File, accept: (String) -> Boolean): List<String> =
        ZipFile(file).use { zip ->
            val result = mutableListOf<String>()
            val entries = zip.entries()
            var entryCount = 0
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                entryCount++
                require(entryCount <= MAX_APK_ZIP_ENTRIES) {
                    "APK contains too many ZIP entries to inspect safely"
                }
                if (accept(name)) result += name
            }
            result
        }

    private fun hashBytes(bytes: ByteArray, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
