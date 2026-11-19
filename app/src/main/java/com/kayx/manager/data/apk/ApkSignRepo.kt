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

    private fun writeSecret(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        tmp.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        check(tmp.renameTo(target)) { "Could not save ${target.name}" }
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
        keyFile.delete(); certFile.delete()
        loadOrCreate(ctx)
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
        writeSecret(keyFile, keyDer)
        writeSecret(certFile, cert.encoded)
    }

    fun exportTo(ctx: Context, dir: File): Result<String> = runCatching {
        loadOrCreate(ctx)
        val (keyFile, certFile) = keyFiles(ctx)
        dir.mkdirs()
        keyFile.copyTo(File(dir, "kayx.pk8"), overwrite = true)
        certFile.copyTo(File(dir, "kayx.x509"), overwrite = true)
        dir.absolutePath
    }

    @Synchronized
    private fun loadOrCreate(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val (keyFile, certFile) = keyFiles(ctx)
        if (keyFile.exists() || certFile.exists()) {
            try {
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
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=KayX, O=KayX")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L), name, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        writeSecret(keyFile, pair.private.encoded)
        writeSecret(certFile, cert.encoded)
        return pair.private to cert
    }

    fun sign(ctx: Context, input: File, output: File): Result<Unit> = runCatching {
        require(input.canonicalFile != output.canonicalFile) { "Output must be a different file than the input APK" }
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
