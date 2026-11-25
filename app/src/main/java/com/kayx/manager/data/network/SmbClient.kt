package com.kayx.manager.data.network

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OVERWRITE_IF
import com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_DELETE
import com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ
import com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_WRITE
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.protocol.commons.EnumWithValue
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB2/3 client. Credentials are passed only to the SMB authentication exchange. */
class SmbClient {
    data class Entry(val name: String, val directory: Boolean, val size: Long)

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var share: DiskShare? = null

    @Synchronized
    fun connect(host: String, port: Int, shareName: String, username: String, password: String, domain: String = "") {
        disconnect()
        require(host.isNotBlank() && host.length <= 253 && host.none { it.code < 0x20 }) { "Invalid SMB host" }
        require(port in 1..65535) { "Invalid SMB port" }
        require(shareName.isNotBlank() && shareName.length <= 80 && shareName.none { it == '/' || it == '\\' || it.code < 0x20 }) { "Invalid SMB share name" }
        require(username.isNotBlank() && username.none { it.code < 0x20 }) { "Invalid SMB username" }
        // Require SMB3 encryption and signing; do not silently downgrade to SMB2/unencrypted SMB.
        val config = SmbConfig.builder()
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
            .withEncryptData(true)
            .withSigningRequired(true)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        val smbClient = SMBClient(config)
        try {
            val conn = smbClient.connect(host, port)
            val session = conn.authenticate(AuthenticationContext(username, password.toCharArray(), domain))
            val disk = session.connectShare(shareName) as? DiskShare
                ?: throw SmbException("The selected SMB share is not a disk share")
            client = smbClient
            connection = conn
            share = disk
        } catch (failure: Throwable) {
            runCatching { smbClient.close() }
            throw SmbException("SMB connection failed: ${failure.message}")
        }
    }

    fun list(path: String = ""): List<Entry> {
        val remote = validateSmbPath(path)
        return requireShare().list(remote).asSequence()
            .filter { it.fileName != "." && it.fileName != ".." && validateSmbName(it.fileName) }
            .take(MAX_ENTRIES)
            .map {
                Entry(
                    it.fileName,
                    EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    it.endOfFile.coerceAtLeast(0L)
                )
            }.toList()
    }

    fun download(path: String, output: OutputStream, onBytes: (Long) -> Unit = {}) {
        val file = requireShare().openFile(
            validateSmbPath(path), EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
            SHARE_ACCESS, FILE_OPEN, EnumSet.of(FILE_NON_DIRECTORY_FILE)
        )
        file.use { it.inputStream.use { input -> copy(input, output, onBytes) } }
    }

    fun upload(path: String, input: InputStream, onBytes: (Long) -> Unit = {}) {
        val target = validateSmbPath(path)
        val temporary = "$target.kayx-part"
        val file = requireShare().openFile(
            temporary, EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE), null,
            SHARE_ACCESS, FILE_OVERWRITE_IF, EnumSet.of(FILE_NON_DIRECTORY_FILE)
        )
        try {
            file.use { it.outputStream.use { output -> copy(input, output, onBytes) } }
            // Keep the original untouched until the temporary transfer has fully closed.
            requireShare().openFile(
                temporary, EnumSet.of(com.hierynomus.msdtyp.AccessMask.DELETE), null,
                SHARE_ACCESS, FILE_OPEN, EnumSet.of(FILE_NON_DIRECTORY_FILE)
            ).use { it.rename(target, false) }
        } catch (failure: Throwable) {
            runCatching { requireShare().rm(temporary) }
            throw failure
        }
    }

    fun createDirectory(path: String) = requireShare().mkdir(validateSmbPath(path))
    fun deleteFile(path: String) = requireShare().rm(validateSmbPath(path))
    fun deleteDirectory(path: String) = requireShare().rmdir(validateSmbPath(path), false)
    fun rename(source: String, destination: String, directory: Boolean = false) {
        val from = validateSmbPath(source)
        val to = validateSmbPath(destination)
        if (directory) {
            requireShare().openDirectory(
                from, EnumSet.of(com.hierynomus.msdtyp.AccessMask.DELETE), null,
                SHARE_ACCESS, FILE_OPEN, EnumSet.of(FILE_DIRECTORY_FILE)
            ).use { it.rename(to, false) }
        } else {
            requireShare().openFile(
                from, EnumSet.of(com.hierynomus.msdtyp.AccessMask.DELETE), null,
                SHARE_ACCESS, FILE_OPEN, EnumSet.of(FILE_NON_DIRECTORY_FILE)
            ).use { it.rename(to, false) }
        }
    }

    @Synchronized
    fun cancelTransfer() = disconnect()

    @Synchronized
    fun disconnect() {
        runCatching { share?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        connection = null
        client = null
    }

    private fun requireShare(): DiskShare =
        share ?: throw SmbException("Not connected")

    private fun copy(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
            onBytes(total)
        }
        output.flush()
    }

    class SmbException(message: String) : Exception(message)

    private companion object {
        const val MAX_ENTRIES = 5000
        val SHARE_ACCESS: EnumSet<SMB2ShareAccess> =
            EnumSet.of(FILE_SHARE_READ, FILE_SHARE_WRITE, FILE_SHARE_DELETE)
    }
}

internal fun validateSmbPath(path: String): String {
    require(path.length <= 4096 && !path.startsWith("/") && !path.startsWith("\\") &&
        path.split('\\').none { it == ".." || it == "." } &&
        path.none { it == '/' || it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 }) {
        "Invalid SMB relative path"
    }
    return path
}

internal fun validateSmbName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name.length <= 255 &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 }
