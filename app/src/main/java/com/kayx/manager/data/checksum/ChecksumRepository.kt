package com.kayx.manager.data.checksum

import com.kayx.manager.core.NativeCore
import java.io.File
import java.security.MessageDigest

data class Hashes(val md5: String, val sha1: String, val sha256: String, val crc32: String = "")

class ChecksumRepository {
    fun compute(file: File): Hashes {
        NativeCore.hashes(file.absolutePath)?.let { return Hashes(it[0], it[1], it[2], it[3]) }
        val md5 = MessageDigest.getInstance("MD5"); val sha1 = MessageDigest.getInstance("SHA-1"); val sha256 = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
            }
        }
        fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }
        return Hashes(hex(md5), hex(sha1), hex(sha256))
    }
}
