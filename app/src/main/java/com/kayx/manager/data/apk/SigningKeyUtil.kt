package com.kayx.manager.data.apk

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Pure-JVM helpers for importing signing keys. */
internal object SigningKeyUtil {
    fun pemToDer(b: ByteArray): ByteArray {
        val text = String(b, Charsets.ISO_8859_1)
        if (!text.contains("-----BEGIN")) return b
        require(!text.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            "Encrypted private keys are not supported. Decrypt it first (openssl pkcs8 -nocrypt)."
        }
        require(!text.contains("BEGIN RSA PRIVATE KEY") && !text.contains("BEGIN EC PRIVATE KEY")) {
            "PKCS#1/SEC1 keys are not supported. Convert to unencrypted PKCS#8 first."
        }
        val body = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(body)
    }

    fun parseKey(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
    }

    fun keyMatches(key: PrivateKey, pub: PublicKey): Boolean = runCatching {
        val algorithm = when (key.algorithm.uppercase()) {
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> "SHA256withRSA"
        }
        val challenge = "kayx-key-check".toByteArray()
        val signature = Signature.getInstance(algorithm).apply {
            initSign(key); update(challenge)
        }.sign()
        Signature.getInstance(algorithm).apply {
            initVerify(pub); update(challenge)
        }.verify(signature)
    }.getOrDefault(false)
}
