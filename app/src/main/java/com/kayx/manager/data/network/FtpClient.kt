package com.kayx.manager.data.network

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Minimal plain-FTP client on raw sockets (no third-party library). Not encrypted: only for trusted networks. */
class FtpClient {
    data class Entry(val name: String, val dir: Boolean, val size: Long, val modified: String)
    class FtpException(msg: String) : Exception(msg)

    private var sock: Socket? = null
    private var rd: BufferedReader? = null
    private var wr: BufferedWriter? = null
    private var host = ""
    private var secureControl = false
    @Volatile private var activeDataSocket: Socket? = null

    private fun clean(s: String): String {
        if (s.length > 4096 || s.any { it == '\r' || it == '\n' || it == '\u0000' || it.code < 0x20 }) {
            throw FtpException("FTP command contains an invalid control character")
        }
        return s
    }

    private fun cleanName(s: String): String = try {
        validateFtpRemoteName(s)
    } catch (e: IllegalArgumentException) {
        throw FtpException(e.message ?: "Invalid remote name")
    }

    private fun reply(): Pair<Int, String> {
        val r = rd ?: throw FtpException("Not connected")
        val first = r.readLine() ?: throw FtpException("Connection closed")
        val code = first.take(3).toIntOrNull() ?: throw FtpException("Bad reply: $first")
        val sb = StringBuilder(first)
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val l = r.readLine() ?: throw FtpException("Connection closed during multiline reply")
                sb.append('\n').append(l)
                if (sb.length > MAX_REPLY_CHARS) throw FtpException("FTP reply is too large")
                if (l.length >= 4 && l.startsWith(code.toString()) && l[3] == ' ') break
            }
        }
        return code to sb.toString()
    }

    private fun send(cmd: String): Pair<Int, String> {
        val w = wr ?: throw FtpException("Not connected")
        w.write(cmd + "\r\n"); w.flush()
        return reply()
    }

    private fun expect(cmd: String, vararg ok: Int): String {
        val (c, m) = send(cmd)
        if (c !in ok) throw FtpException(m.lines().last().ifBlank { m })
        return m
    }

    fun connect(host: String, port: Int, user: String, pass: String, secure: Boolean = false) {
        disconnect()
        if (host.isBlank()) throw FtpException("Host is required")
        if (port !in 1..65535) throw FtpException("Port must be between 1 and 65535")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 15_000)
            s.soTimeout = 30_000
            sock = s
            this.host = host
            rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            wr = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            val (c0, m0) = reply()
            if (c0 != 220) throw FtpException(m0)
            // Negotiate TLS before sending USER/PASS; never leak credentials on FTPS.
            if (secure) {
                val (authCode, authMessage) = send("AUTH TLS")
                if (authCode != 234) throw FtpException(authMessage)
                val tls = tlsSocket(s, host, port)
                sock = tls
                rd = BufferedReader(InputStreamReader(tls.getInputStream(), Charsets.UTF_8))
                wr = BufferedWriter(OutputStreamWriter(tls.getOutputStream(), Charsets.UTF_8))
                expect("PBSZ 0", 200)
                expect("PROT P", 200)
                secureControl = true
            }
            val (c1, m1) = send("USER ${clean(user)}")
            var code = c1
            var msg = m1
            if (c1 == 331 || c1 == 332) {
                val p = send("PASS ${clean(pass)}")
                code = p.first
                msg = p.second
            }
            if (code != 230) throw FtpException(msg)
            runCatching { send("OPTS UTF8 ON") }
            expect("TYPE I", 200)
        } catch (failure: Throwable) {
            runCatching { s.close() }
            sock = null
            rd = null
            wr = null
            this.host = ""
            secureControl = false
            throw failure
        }
    }

    /** Abort an active transfer without sending QUIT concurrently with its control reply. */
    fun cancelTransfer() {
        runCatching { activeDataSocket?.close() }
        activeDataSocket = null
        // Closing the control socket unblocks a transfer waiting for its final FTP reply.
        runCatching { sock?.close() }
        sock = null; rd = null; wr = null
        host = ""
        secureControl = false
    }

    fun disconnect() {
        runCatching { activeDataSocket?.close() }
        activeDataSocket = null
        runCatching { if (sock != null) send("QUIT") }
        runCatching { sock?.close() }
        sock = null; rd = null; wr = null
        host = ""
        secureControl = false
    }

    fun pwd(): String {
        val m = expect("PWD", 257)
        val a = m.indexOf('"')
        val b = m.indexOf('"', a + 1)
        return if (a >= 0 && b > a) m.substring(a + 1, b) else "/"
    }

    fun cwd(path: String) { expect("CWD ${clean(path)}", 250) }
    fun up() { expect("CDUP", 200, 250) }
    fun mkdir(name: String) { expect("MKD ${cleanName(name)}", 257) }
    fun delete(name: String) { expect("DELE ${cleanName(name)}", 250) }
    fun rmdir(name: String) { expect("RMD ${cleanName(name)}", 250) }
    fun rename(from: String, to: String) { expect("RNFR ${cleanName(from)}", 350); expect("RNTO ${cleanName(to)}", 250) }

    private fun openData(): Socket {
        val (c, m) = send("EPSV")
        val port = if (c == 229) {
            parseFtpEpsvPort(m) ?: throw FtpException("Invalid EPSV reply or port")
        } else {
            val (c2, m2) = send("PASV")
            if (c2 != 227) throw FtpException(m2)
            parseFtpPasvPort(m2) ?: throw FtpException("Invalid PASV reply or port")
        }
        val d = Socket()
        try {
            d.connect(InetSocketAddress(host, port), 15_000)
            d.soTimeout = 30_000
            return d
        } catch (failure: Throwable) {
            runCatching { d.close() }
            throw failure
        }
    }

    private fun tlsSocket(plain: Socket, tlsHost: String, tlsPort: Int): Socket {
        val ssl = SSLContext.getDefault().socketFactory
            .createSocket(plain, tlsHost, tlsPort, true) as SSLSocket
        try {
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = parameters
            ssl.startHandshake()
            return ssl
        } catch (failure: Throwable) {
            runCatching { ssl.close() }
            throw FtpException("FTPS certificate/hostname verification failed: ${failure.message}")
        }
    }

    private fun protectDataSocket(socket: Socket): Socket =
        if (secureControl) tlsSocket(socket, host, socket.port) else socket

    private fun listing(cmd: String): List<String>? {
        val ds = openData()
        activeDataSocket = ds
        try {
            val (c, _) = send(cmd)
            if (c != 150 && c != 125) return null
            val protected = protectDataSocket(ds)
            var exceededLimit = false
            val lines = protected.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val result = ArrayList<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.length > MAX_LISTING_LINE_CHARS || result.size >= MAX_LISTING_ENTRIES) {
                        exceededLimit = true
                    } else if (!exceededLimit) {
                        result += line
                    }
                }
                result
            }
            val (c2, m2) = reply()
            if (c2 != 226 && c2 != 250) throw FtpException(m2)
            if (exceededLimit) throw FtpException("FTP listing exceeds the safety limit")
            return lines
        } finally {
            if (activeDataSocket === ds) activeDataSocket = null
            runCatching { ds.close() }
        }
    }

    fun list(): List<Entry> {
        listing("MLSD")?.let { return parseMlsd(it) }
        listing("LIST")?.let { return parseUnix(it) }
        throw FtpException("Directory listing failed")
    }

    fun download(remote: String, out: OutputStream, onBytes: (Long) -> Unit) {
        val ds = openData()
        activeDataSocket = ds
        try {
            val (c, m) = send("RETR ${clean(remote)}")
            if (c != 150 && c != 125) throw FtpException(m)
            protectDataSocket(ds).getInputStream().use { copy(it, out, onBytes) }
            val (c2, m2) = reply()
            if (c2 != 226 && c2 != 250) throw FtpException(m2)
        } finally {
            if (activeDataSocket === ds) activeDataSocket = null
            runCatching { ds.close() }
        }
    }

    /**
     * Resume a RETR only after the server confirms the current remote size with SIZE.
     * The caller must append to a partial file whose existing length equals [offset].
     * Returns the verified remote size; byte callbacks include the starting offset.
     */
    fun downloadResumable(remote: String, out: OutputStream, offset: Long, onBytes: (Long) -> Unit = {}): Long {
        val safeRemote = clean(remote)
        val (sizeCode, sizeReply) = send("SIZE $safeRemote")
        if (sizeCode != 213) throw FtpException("Server does not provide a verifiable SIZE response: $sizeReply")
        val remoteSize = sizeReply.substringAfter(' ', "").trim().toLongOrNull()
            ?: throw FtpException("Invalid FTP SIZE response")
        validateFtpResumeOffset(offset, remoteSize)
        if (offset == remoteSize) return remoteSize

        val ds = openData()
        activeDataSocket = ds
        try {
            if (offset > 0L) expect("REST $offset", 350)
            val (code, message) = send("RETR $safeRemote")
            if (code != 150 && code != 125) throw FtpException(message)
            protectDataSocket(ds).getInputStream().use { copy(it, out) { transferred -> onBytes(offset + transferred) } }
            val (finalCode, finalMessage) = reply()
            if (finalCode != 226 && finalCode != 250) throw FtpException(finalMessage)
            return remoteSize
        } finally {
            if (activeDataSocket === ds) activeDataSocket = null
            runCatching { ds.close() }
        }
    }

    /** Upload to a unique sibling first; publish the destination only after transfer completion. */
    fun upload(remote: String, input: InputStream, onBytes: (Long) -> Unit = {}) {
        val destination = cleanName(remote)
        val temporary = ".kayx-upload-${UUID.randomUUID()}.part"
        try {
            uploadDirect(temporary, input, onBytes)
            rename(temporary, destination)
        } catch (failure: Throwable) {
            runCatching { delete(temporary) }
            throw failure
        }
    }

    /**
     * Resume into a caller-persisted temporary sibling. [offset] must equal the
     * server-reported temporary size; publication happens only after final SIZE verification.
     * The caller must persist [temporary] and the acknowledged offset across process restarts.
     */
    fun uploadResumable(
        remote: String,
        temporary: String,
        input: InputStream,
        offset: Long,
        totalSize: Long,
        onBytes: (Long) -> Unit = {}
    ) {
        val destination = cleanName(remote)
        val temp = cleanName(temporary)
        require(temp != destination) { "Temporary upload name must differ from destination" }
        require(offset >= 0L && totalSize >= 0L && offset <= totalSize) { "Invalid upload resume range" }
        val (sizeCode, sizeReply) = send("SIZE $temp")
        val remoteSize = when (sizeCode) {
            213 -> sizeReply.substringAfter(' ', "").trim().toLongOrNull()
                ?: throw FtpException("Invalid temporary upload SIZE response")
            550 -> 0L
            else -> throw FtpException("Cannot verify temporary upload size: $sizeReply")
        }
        validateFtpUploadResumeState(offset, totalSize, remoteSize)
        if (offset == totalSize) {
            rename(temp, destination)
            return
        }
        val ds = openData()
        activeDataSocket = ds
        try {
            val (code, message) = send(if (offset == 0L) "STOR $temp" else "APPE $temp")
            if (code != 150 && code != 125) throw FtpException(message)
            protectDataSocket(ds).getOutputStream().use { copy(input, it) { sent ->
                if (offset > Long.MAX_VALUE - sent || offset + sent > totalSize) {
                    throw FtpException("Upload source exceeded the declared size")
                }
                onBytes(offset + sent)
            } }
            val (finalCode, finalMessage) = reply()
            if (finalCode != 226 && finalCode != 250) throw FtpException(finalMessage)
        } finally {
            if (activeDataSocket === ds) activeDataSocket = null
            runCatching { ds.close() }
        }
        val (verifyCode, verifyReply) = send("SIZE $temp")
        val verifiedSize = if (verifyCode == 213) verifyReply.substringAfter(' ', "").trim().toLongOrNull() else null
        if (verifiedSize != totalSize) throw FtpException("Temporary upload size verification failed")
        rename(temp, destination)
    }

    private fun uploadDirect(remote: String, input: InputStream, onBytes: (Long) -> Unit) {
        val ds = openData()
        activeDataSocket = ds
        try {
            val (c, m) = send("STOR ${cleanName(remote)}")
            if (c != 150 && c != 125) throw FtpException(m)
            protectDataSocket(ds).getOutputStream().use { copy(input, it, onBytes) }
            val (c2, m2) = reply()
            if (c2 != 226 && c2 != 250) throw FtpException(m2)
        } finally {
            if (activeDataSocket === ds) activeDataSocket = null
            runCatching { ds.close() }
        }
    }

    private companion object {
        const val MAX_REPLY_CHARS = 16 * 1024
        const val MAX_LISTING_LINE_CHARS = 4 * 1024
        const val MAX_LISTING_ENTRIES = 5_000
    }

    private fun copy(i: InputStream, o: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            total += n
            onBytes(total)
        }
        o.flush()
    }

    internal fun parseMlsdForTest(lines: List<String>): List<Entry> = parseMlsd(lines)

    private fun parseMlsd(lines: List<String>): List<Entry> = lines.mapNotNull { line ->
        val sp = line.indexOf(' ')
        if (sp <= 0) return@mapNotNull null
        val facts = line.substring(0, sp).split(';').filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val name = line.substring(sp + 1)
        val type = facts["type"]?.lowercase() ?: "file"
        if (type == "cdir" || type == "pdir" || name == "." || name == ".." ||
            runCatching { validateFtpRemoteName(name) }.isFailure) return@mapNotNull null
        val m = facts["modify"].orEmpty()
        val mod = if (m.length >= 12) "${m.substring(0, 4)}-${m.substring(4, 6)}-${m.substring(6, 8)} ${m.substring(8, 10)}:${m.substring(10, 12)}" else ""
        Entry(name, type == "dir", facts["size"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L, mod)
    }

    internal fun parseUnixForTest(lines: List<String>): List<Entry> = parseUnix(lines)

    private val unixLine = Regex("^([\\-dl])[rwxsStT\\-]{9}\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w{3}\\s+\\d{1,2}\\s+[\\d:]{4,5})\\s+(.+)$")

    private fun parseUnix(lines: List<String>): List<Entry> = lines.mapNotNull { line ->
        val m = unixLine.find(line.trim()) ?: return@mapNotNull null
        val name = m.groupValues[4].substringBefore(" -> ")
        if (name == "." || name == ".." || runCatching { validateFtpRemoteName(name) }.isFailure) return@mapNotNull null
        Entry(name, m.groupValues[1] == "d", m.groupValues[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L, m.groupValues[3])
    }
}

internal fun validateFtpResumeOffset(offset: Long, remoteSize: Long): Long {
    require(remoteSize >= 0L) { "Remote file size is invalid" }
    require(offset >= 0L && offset <= remoteSize) { "Resume offset is outside the current remote file" }
    return offset
}

internal fun validateFtpRemoteName(name: String): String {
    require(name.isNotBlank()) { "Remote name is required" }
    require(name != "." && name != "..") { "Invalid remote name" }
    require(name.length <= 255) { "Remote name is too long" }
    require(name.none { it == '/' || it == '\\' || it == '\r' || it == '\n' || it == '\u0000' || it.code < 0x20 }) {
        "Remote name must be a single safe path segment"
    }
    return name
}


internal fun parseFtpEpsvPort(reply: String): Int? {
    val match = Regex("""\((.)(?:\1){2}(\d+)\1\)""").find(reply) ?: return null
    return match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 }
}

internal fun parseFtpPasvPort(reply: String): Int? {
    val values = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""")
        .find(reply)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() } ?: return null
    if (values.size != 6 || values.any { it !in 0..255 }) return null
    return (values[4] * 256 + values[5]).takeIf { it in 1..65535 }
}


internal fun validateFtpUploadResumeState(offset: Long, totalSize: Long, remoteSize: Long) {
    require(totalSize >= 0L && offset >= 0L && offset <= totalSize) { "Invalid upload resume range" }
    require(remoteSize == offset) { "Temporary upload size changed; restart this upload" }
}
