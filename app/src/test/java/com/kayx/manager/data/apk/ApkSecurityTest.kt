package com.kayx.manager.data.apk

import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test

class ApkSecurityTest {
    @Test fun importedPrivateKeyMustMatchCertificatePublicKey() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val first = generator.generateKeyPair()
        val second = generator.generateKeyPair()
        assertTrue(SigningKeyUtil.keyMatches(first.private, first.public))
        assertFalse(SigningKeyUtil.keyMatches(first.private, second.public))
    }

    @Test fun rejectsEncryptedAndLegacyPkcs1Pem() {
        val encrypted = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAA==\n-----END ENCRYPTED PRIVATE KEY-----".toByteArray()
        val pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nAA==\n-----END RSA PRIVATE KEY-----".toByteArray()
        assertTrue(runCatching { SigningKeyUtil.pemToDer(encrypted) }.isFailure)
        assertTrue(runCatching { SigningKeyUtil.pemToDer(pkcs1) }.isFailure)
    }

    @Test fun removesOnlyDirectV1SignatureFiles() {
        assertTrue(ApkPatcher.isV1SignatureFile("META-INF/MANIFEST.MF"))
        assertTrue(ApkPatcher.isV1SignatureFile("META-INF/CERT.SF"))
        assertTrue(ApkPatcher.isV1SignatureFile("META-INF/CERT.RSA"))
        assertFalse(ApkPatcher.isV1SignatureFile("META-INF/services/provider"))
        assertFalse(ApkPatcher.isV1SignatureFile("assets/CERT.SF"))
        assertFalse(ApkPatcher.isV1SignatureFile("META-INF/nested/CERT.SF"))
    }
}
