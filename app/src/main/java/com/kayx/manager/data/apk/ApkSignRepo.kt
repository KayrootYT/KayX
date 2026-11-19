package com.kayx.manager.data.apk

import android.content.Context
import com.android.apksig.ApkSigner
import com.kayx.manager.data.archive.ArchiveEdit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

object ApkSignRepo {
    private fun keyFiles(ctx: Context) = File(ctx.filesDir, "kayx-sign.key") to File(ctx.filesDir, "kayx-sign.crt")

    private fun parseKey(der: ByteArray): PrivateKey = SigningKeyUtil.parseKey(der)
    private fun pemToDer(bytes: ByteArray): ByteArray = SigningKeyUtil.pemToDer(bytes)

    /**
     * Stage both halves before replacing either. If either rename fails, restore the previous
     * key/certificate pair so an interrupted import/regeneration cannot leave a mixed identity.
     */
    private fun writeSecretPair(keyFile: File, keyBytes: ByteArray, certFile: File, certBytes: ByteArray) {
        val keyStage = File(keyFile.parentFile, keyFile.name + ".new")
        val certStage = File(certFile.parentFile, certFile.name + ".new")
        val keyBackup = File(keyFile.parentFile, keyFile.name + ".bak")
        val certBackup = File(certFile.parentFile, certFile.name + ".bak")
        listOf(keyStage, certStage, keyBackup, certBackup).forEach { it.delete() }

        fun writeStage(file: File, bytes: ByteArray) {
            file.outputStream().use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }

        try {
            writeStage(keyStage, keyBytes)
            writeStage(certStage, certBytes)

            val hadKey = keyFile.exists()
            val hadCert = certFile.exists()
            if (hadKey) check(keyFile.renameTo(keyBackup)) { "Could not stage previous signing key" }
            if (hadCert) {
                if (!certFile.renameTo(certBackup)) {
                    if (hadKey) keyBackup.renameTo(keyFile)
                    error("Could not stage previous signing certificate")
                }
            }

            try {
                check(keyStage.renameTo(keyFile)) { "Could not install new signing key" }
                check(certStage.renameTo(certFile)) { "Could not install new signing certificate" }
            } catch (t: Throwable) {
                keyFile.delete()
                certFile.delete()
                if (hadKey) check(keyBackup.renameTo(keyFile)) { "Could not restore previous signing key" }
                if (hadCert) check(certBackup.renameTo(certFile)) { "Could not restore previous signing certificate" }
                throw t
            }
        } finally {
            keyStage.delete()
            certStage.delete()
            // Backups are removed only after both new files are installed successfully.
            if (keyFile.exists() && certFile.exists()) {
                keyBackup.delete()
                certBackup.delete()
            }
        }
    }

    data class KeyInfo(val subject: String, val sha256: String, val sha1: String, val validUntil: Date, val algo: String)

    fun info(ctx: Context): Result<KeyInfo> = runCatching {
        val (_, cert) = loadOrCreate(ctx)
        fun fingerprint(algorithm: String) =
            java.security.MessageDigest.getInstance(algorithm).digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        KeyInfo(cert.subjectX500Principal.name, fingerprint("SHA-256"), fingerprint("SHA-1"), cert.notAfter, cert.sigAlgName)
    }

    @Synchronized
    fun regenerate(ctx: Context): Result<Unit> = runCatching {
        val (keyFile, certFile) = keyFiles(ctx)
        val (key, cert) = createIdentity()
        writeSecretPair(keyFile, key.encoded, certFile, cert.encoded)
        Unit
    }

    @Synchronized
    fun importKey(ctx: Context, keyBytes: ByteArray, certBytes: ByteArray): Result<Unit> = runCatching {
        val keyDer = pemToDer(keyBytes)
        val key = parseKey(keyDer)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(pemToDer(certBytes))) as X509Certificate
        require(SigningKeyUtil.keyMatches(key, cert.publicKey)) { "The private key does not match the certificate" }
        val (keyFile, certFile) = keyFiles(ctx)
        writeSecretPair(keyFile, keyDer, certFile, cert.encoded)
    }

    fun exportTo(ctx: Context, dir: File): Result<String> = runCatching {
        loadOrCreate(ctx)
        val (keyFile, certFile) = keyFiles(ctx)
        check(dir.exists() || dir.mkdirs()) { "Cannot create export directory" }
        keyFile.copyTo(File(dir, "kayx.pk8"), overwrite = true)
        certFile.copyTo(File(dir, "kayx.x509"), overwrite = true)
        dir.absolutePath
    }

    private fun createIdentity(): Pair<PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=KayX, O=KayX")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L), name, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        return pair.private to JcaX509CertificateConverter().getCertificate(holder)
    }

    @Synchronized
    private fun loadOrCreate(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val (keyFile, certFile) = keyFiles(ctx)
        if (keyFile.exists() || certFile.exists()) {
            try {
                require(keyFile.isFile && certFile.isFile) { "Signing key pair is incomplete" }
                val key = parseKey(keyFile.readBytes())
                val certBytes = certFile.inputStream().use { it.readBytes() }
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
                require(SigningKeyUtil.keyMatches(key, cert.publicKey)) { "Saved signing key does not match its certificate" }
                return key to cert
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "The saved signing key is unreadable or mismatched. Import your key again, or regenerate a new one (this changes the signature).", t
                )
            }
        }
        val (key, cert) = createIdentity()
        writeSecretPair(keyFile, key.encoded, certFile, cert.encoded)
        return key to cert
    }

    fun sign(ctx: Context, input: File, output: File): Result<Unit> = runCatching {
        require(input.isFile) { "Input APK does not exist" }
        require(input.canonicalFile != output.canonicalFile) { "Output must be a different file than the input APK" }
        output.parentFile?.let { check(it.exists() || it.mkdirs()) { "Cannot create output folder" } }
        val (key, cert) = loadOrCreate(ctx)
        val aligned = File(output.parentFile, output.name + ".aligned")
        try {
            input.copyTo(aligned, overwrite = true)
            ArchiveEdit.rewrite(aligned).getOrThrow()
            val config = ApkSigner.SignerConfig.Builder("KayX", key, listOf(cert)).build()
            ApkSigner.Builder(listOf(config))
                .setInputApk(aligned).setOutputApk(output)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .build().sign()
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally {
            aligned.delete()
        }
    }
}
