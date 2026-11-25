package com.kayx.manager.data.network

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.Vector

/** SFTP client that refuses unknown SSH host keys; the supplied known_hosts file is mandatory. */
class SftpClient {
    data class Entry(val name: String, val directory: Boolean, val size: Long, val modifiedEpochSeconds: Long)

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelSftp? = null

    @Synchronized
    fun connect(host: String, port: Int, username: String, password: String, knownHosts: File) {
        disconnect()
        require(host.isNotBlank() && host.length <= 253) { "Invalid SFTP host" }
        require(port in 1..65535) { "Invalid SFTP port" }
        require(username.isNotBlank() && username.none { it.code < 0x20 }) { "Invalid SFTP username" }
        require(knownHosts.isFile && knownHosts.length() > 0L) { "Import a verified SSH known_hosts file before connecting" }

        val jsch = JSch()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val candidate = jsch.getSession(username, host, port)
        try {
            candidate.setPassword(password)
            candidate.setConfig("StrictHostKeyChecking", "yes")
            candidate.setConfig("PreferredAuthentications", "publickey,password")
            candidate.connect(CONNECT_TIMEOUT_MS)
            val sftp = candidate.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            session = candidate
            channel = sftp
        } catch (failure: Throwable) {
            runCatching { candidate.disconnect() }
            throw SftpException("SFTP connection failed; verify the server key is in known_hosts: ${failure.message}")
        }
    }

    fun list(path: String = "/"): List<Entry> {
        val sftp = requireChannel()
        validateSftpPath(path)
        @Suppress("UNCHECKED_CAST")
        val items = sftp.ls(path) as Vector<ChannelSftp.LsEntry>
        return items.asSequence()
            .filter { it.filename != "." && it.filename != ".." && validateSftpName(it.filename) }
            .take(MAX_ENTRIES)
            .map { Entry(it.filename, it.attrs.isDir, it.attrs.size, it.attrs.mTime.toLong()) }
            .toList()
    }

    fun download(remotePath: String, output: OutputStream, onBytes: (Long) -> Unit = {}) {
        validateSftpPath(remotePath)
        requireChannel().get(remotePath).use { copy(it, output, onBytes) }
    }

    fun upload(remotePath: String, input: InputStream, onBytes: (Long) -> Unit = {}) {
        validateSftpPath(remotePath)
        val sftp = requireChannel()
        val temporary = "$remotePath.kayx-part"
        try {
            sftp.put(temporary).use { output -> copy(input, output, onBytes) }
            // Rename only after a complete upload; do not overwrite an existing destination.
            sftp.rename(temporary, remotePath)
        } catch (failure: Throwable) {
            runCatching { sftp.rm(temporary) }
            throw failure
        }
    }

    fun createDirectory(path: String) { validateSftpPath(path); requireChannel().mkdir(path) }
    fun deleteFile(path: String) { validateSftpPath(path); requireChannel().rm(path) }
    fun deleteDirectory(path: String) { validateSftpPath(path); requireChannel().rmdir(path) }
    fun rename(source: String, destination: String) {
        validateSftpPath(source); validateSftpPath(destination)
        requireChannel().rename(source, destination)
    }

    @Synchronized
    fun cancelTransfer() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    @Synchronized
    fun disconnect() = cancelTransfer()

    private fun requireChannel(): ChannelSftp =
        channel?.takeIf { it.isConnected } ?: throw SftpException("Not connected")

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

    class SftpException(message: String) : Exception(message)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val MAX_ENTRIES = 5000
    }
}

internal fun validateSftpPath(path: String): String {
    require(path.isNotBlank() && path.length <= 4096) { "Invalid SFTP path" }
    require(path.none { it == '\r' || it == '\n' || it == '\u0000' || it.code < 0x20 }) { "SFTP path contains control characters" }
    require(path.split('/').none { it == ".." || it == "." }) { "SFTP path traversal is not allowed" }
    return path
}

internal fun validateSftpName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name.length <= 255 &&
        name.none { it == '/' || it == '\\' || it == '\r' || it == '\n' || it == '\u0000' || it.code < 0x20 }
