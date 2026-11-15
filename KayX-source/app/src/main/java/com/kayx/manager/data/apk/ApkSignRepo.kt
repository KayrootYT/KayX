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
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Signs APKs (v1 + v2 + v3) with a persistent self-generated KayX key stored in app-private storage. */
object ApkSignRepo {
    private fun keyFiles(ctx: Context) = File(ctx.filesDir, "kayx-sign.key") to File(ctx.filesDir, "kayx-sign.crt")

    private fun parseKey(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }.getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
    }

    private fun pemToDer(b: ByteArray): ByteArray {
        val t = String(b, Charsets.ISO_8859_1)
        if (!t.contains("-----BEGIN")) return b
        val body = t.lines().filter { !it.startsWith("-----") }.joinToString("")
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }

    data class KeyInfo(val subject: String, val sha256: String, val sha1: String, val validUntil: Date, val algo: String)

    fun info(ctx: Context): Result<KeyInfo> = runCatching {
        val (_, cert) = loadOrCreate(ctx)
        fun fp(a: String) = java.security.MessageDigest.getInstance(a).digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        KeyInfo(cert.subjectX500Principal.name, fp("SHA-256"), fp("SHA-1"), cert.notAfter, cert.sigAlgName)
    }

    fun regenerate(ctx: Context): Result<Unit> = runCatching {
        val (kf, cf) = keyFiles(ctx); kf.delete(); cf.delete(); loadOrCreate(ctx); Unit
    }

    /** Import a PKCS#8 private key (DER or PEM) and an X.509 certificate (DER or PEM). */
    fun importKey(ctx: Context, keyBytes: ByteArray, certBytes: ByteArray): Result<Unit> = runCatching {
        val keyDer = pemToDer(keyBytes)
        parseKey(keyDer)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(java.io.ByteArrayInputStream(pemToDer(certBytes))) as X509Certificate
        val (kf, cf) = keyFiles(ctx)
        kf.writeBytes(keyDer); cf.writeBytes(cert.encoded)
    }

    fun exportTo(ctx: Context, dir: File): Result<String> = runCatching {
        loadOrCreate(ctx)
        val (kf, cf) = keyFiles(ctx)
        dir.mkdirs()
        kf.copyTo(File(dir, "kayx.pk8"), overwrite = true); cf.copyTo(File(dir, "kayx.x509"), overwrite = true)
        dir.absolutePath
    }

    private fun loadOrCreate(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val (kf, cf) = keyFiles(ctx)
        if (kf.exists() && cf.exists()) runCatching {
            val key = parseKey(kf.readBytes())
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream()) as X509Certificate
            return key to cert
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=KayX, O=KayX")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        kf.writeBytes(kp.private.encoded); cf.writeBytes(cert.encoded)
        return kp.private to cert
    }

    /** Aligns then signs [input] into [output]. Existing signatures are replaced. */
    fun sign(ctx: Context, input: File, output: File): Result<Unit> = runCatching {
        val (key, cert) = loadOrCreate(ctx)
        val aligned = File(output.parentFile, output.name + ".aligned")
        try {
            input.copyTo(aligned, overwrite = true)
            ArchiveEdit.rewrite(aligned).getOrThrow()
            val cfg = ApkSigner.SignerConfig.Builder("KayX", key, listOf(cert)).build()
            ApkSigner.Builder(listOf(cfg))
                .setInputApk(aligned).setOutputApk(output)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .build().sign()
        } catch (t: Throwable) { output.delete(); throw t }
        finally { aligned.delete() }
    }
}
