package com.kayx.manager.data.network

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_DIRECTORY_FILE
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
import java.util.UUID

/** SMB2/3 client. Credentials are passed only to the SMB authentication exchange. */
class SmbClient {
    data class Entry(val name: String, val directory: Boolean, val size: Long)

    @Volatile private var client: SMBClient? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var share: DiskShare? = null

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


    /**
     * Resume using a fingerprint captured from the opened SMB file handle.
     * The caller persists the fingerprint beside the local partial before bytes are copied.
     */
    fun downloadResumable(
        path: String,
        output: OutputStream,
        offset: Long,
        expectedFingerprint: String?,
        onFingerprint: (String) -> Unit,
        onBytes: (Long) -> Unit = {}
    ): Long {
        val file = requireShare().openFile(
            validateSmbPath(path), EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
            SHARE_ACCESS, FILE_OPEN, EnumSet.of(FILE_NON_DIRECTORY_FILE)
        )
        file.use {
            val fingerprint = smbFileFingerprint(it.getFileInformation(FileStandardInformation::class.java).endOfFile,
                it.getFileInformation(FileBasicInformation::class.java))
            val remoteSize = it.getFileInformation(FileStandardInformation::class.java).endOfFile
            validateSmbResumeOffset(offset, remoteSize)
            if (!isSmbResumeFingerprintMatch(offset, expectedFingerprint, fingerprint)) {
                throw SmbResumeMismatchException("SMB remote file changed since the partial download began")
            }
            if (offset == 0L) onFingerprint(fingerprint)

            val buffer = ByteArray(64 * 1024)
            var position = offset
            while (position < remoteSize) {
                val count = it.read(buffer, position)
                if (count <= 0) throw SmbException("SMB download ended before the verified file size")
                if (position - offset > remoteSize - offset - count) {
                    throw SmbException("SMB download exceeded the verified file size")
                }
                output.write(buffer, 0, count)
                position += count
                onBytes(position)
            }
            output.flush()

            val finalSize = it.getFileInformation(FileStandardInformation::class.java).endOfFile
            val finalFingerprint = smbFileFingerprint(finalSize, it.getFileInformation(FileBasicInformation::class.java))
            if (finalFingerprint != fingerprint) {
                throw SmbResumeMismatchException("SMB remote file changed during download")
            }
            return remoteSize
        }
    }

    fun upload(path: String, input: InputStream, onBytes: (Long) -> Unit = {}) {
        val target = validateSmbPath(path)
        val parent = target.substringBeforeLast('\\', "")
        val temporaryName = ".kayx-upload-${UUID.randomUUID()}.part"
        val temporary = if (parent.isEmpty()) temporaryName else "$parent\\$temporaryName"
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

    /**
     * Resume into a stable temporary sibling. The SMB server's current temporary-file size
     * must exactly equal [offset]. Data is written at explicit file offsets, then size-checked
     * before the temporary file is atomically renamed to the destination.
     */
    fun uploadResumable(
        path: String,
        temporaryPath: String,
        input: InputStream,
        offset: Long,
        totalSize: Long,
        onBytes: (Long) -> Unit = {}
    ) {
        val target = validateSmbPath(path)
        val temporary = validateSmbPath(temporaryPath)
        require(target != temporary) { "Temporary upload path must differ from destination" }
        require(offset >= 0L && totalSize >= 0L && offset <= totalSize) { "Invalid upload resume range" }
        val disk = requireShare()
        val disposition = if (offset == 0L) FILE_OVERWRITE_IF else FILE_OPEN
        val file = disk.openFile(
            temporary, EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE), null,
            SHARE_ACCESS, disposition, EnumSet.of(FILE_NON_DIRECTORY_FILE)
        )
        file.use {
            val currentSize = it.getFileInformation(FileStandardInformation::class.java).endOfFile
            validateSmbUploadResumeState(offset, totalSize, currentSize)
            val buffer = ByteArray(64 * 1024)
            var position = offset
            while (position < totalSize) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), totalSize - position).toInt())
                if (count < 0) break
                val written = it.write(buffer, position, 0, count)
                if (written != count.toLong()) throw SmbException("SMB server accepted only a partial write")
                position += written
                onBytes(position)
            }
            if (position != totalSize) throw SmbException("Upload source ended before the declared size")
            if (input.read() != -1) throw SmbException("Upload source exceeded the declared size")
            val finalSize = it.getFileInformation(FileStandardInformation::class.java).endOfFile
            if (finalSize != totalSize) throw SmbException("Temporary upload size verification failed")
        }
        // Rename only after the write handle has closed and the complete size is verified.
        disk.openFile(
            temporary, EnumSet.of(com.hierynomus.msdtyp.AccessMask.DELETE), null,
            SHARE_ACCESS, FILE_OPEN, EnumSet.of(FILE_NON_DIRECTORY_FILE)
        ).use { it.rename(target, false) }
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
    class SmbResumeMismatchException(message: String) : Exception(message)

    private companion object {
        const val MAX_ENTRIES = 5000
        val SHARE_ACCESS: EnumSet<SMB2ShareAccess> =
            EnumSet.of(FILE_SHARE_READ, FILE_SHARE_WRITE, FILE_SHARE_DELETE)
    }
}


internal fun smbFileFingerprint(size: Long, basic: FileBasicInformation): String =
    listOf(
        size,
        basic.creationTime.windowsTimeStamp,
        basic.lastWriteTime.windowsTimeStamp,
        basic.changeTime.windowsTimeStamp
    ).joinToString(":")

internal fun validateSmbResumeOffset(offset: Long, remoteSize: Long): Long {
    require(remoteSize >= 0L) { "Remote file size is invalid" }
    require(offset >= 0L && offset <= remoteSize) { "Resume offset is outside the current remote file" }
    return offset
}

internal fun isSmbResumeFingerprintMatch(offset: Long, expected: String?, actual: String): Boolean =
    offset == 0L || (!expected.isNullOrBlank() && expected == actual)

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


internal fun validateSmbUploadResumeState(offset: Long, totalSize: Long, remoteSize: Long) {
    require(totalSize >= 0L && offset >= 0L && offset <= totalSize) { "Invalid upload resume range" }
    require(remoteSize == offset) { "Temporary upload size changed; restart this upload" }
}
