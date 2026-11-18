package com.kayx.manager.data.network

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Minimal plain-FTP client on raw sockets (no third-party library). Not encrypted: only for trusted networks. */
class FtpClient {
    data class Entry(val name: String, val dir: Boolean, val size: Long, val modified: String)
    class FtpException(msg: String) : Exception(msg)

    private var sock: Socket? = null
    private var rd: BufferedReader? = null
    private var wr: BufferedWriter? = null
    private var host = ""

    private fun clean(s: String): String {
        if ('\r' in s || '\n' in s) throw FtpException("Invalid name")
        return s
    }

    private fun reply(): Pair<Int, String> {
        val r = rd ?: throw FtpException("Not connected")
        val first = r.readLine() ?: throw FtpException("Connection closed")
        val code = first.take(3).toIntOrNull() ?: throw FtpException("Bad reply: $first")
        val sb = StringBuilder(first)
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val l = r.readLine() ?: break
                sb.append('\n').append(l)
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

    fun connect(host: String, port: Int, user: String, pass: String) {
        disconnect()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 15_000)
        s.soTimeout = 30_000
        sock = s
        this.host = host
        rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        wr = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        val (c0, m0) = reply()
        if (c0 != 220) throw FtpException(m0)
        val (c1, m1) = send("USER ${clean(user)}")
        var code = c1
        var msg = m1
        if (c1 == 331 || c1 == 332) { val p = send("PASS ${clean(pass)}"); code = p.first; msg = p.second }
        if (code != 230) throw FtpException(msg)
        runCatching { send("OPTS UTF8 ON") }
        expect("TYPE I", 200)
    }

    fun disconnect() {
        runCatching { if (sock != null) send("QUIT") }
        runCatching { sock?.close() }
        sock = null; rd = null; wr = null
    }

    fun pwd(): String {
        val m = expect("PWD", 257)
        val a = m.indexOf('"')
        val b = m.indexOf('"', a + 1)
        return if (a >= 0 && b > a) m.substring(a + 1, b) else "/"
    }

    fun cwd(path: String) { expect("CWD ${clean(path)}", 250) }
    fun up() { expect("CDUP", 200, 250) }
    fun mkdir(name: String) { expect("MKD ${clean(name)}", 257) }
    fun delete(name: String) { expect("DELE ${clean(name)}", 250) }
    fun rmdir(name: String) { expect("RMD ${clean(name)}", 250) }
    fun rename(from: String, to: String) { expect("RNFR ${clean(from)}", 350); expect("RNTO ${clean(to)}", 250) }

    private fun openData(): Socket {
        val (c, m) = send("EPSV")
        val port = if (c == 229) {
            Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(m)?.groupValues?.get(1)?.toIntOrNull() ?: throw FtpException("Bad EPSV reply")
        } else {
            val (c2, m2) = send("PASV")
            if (c2 != 227) throw FtpException(m2)
            val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(m2)?.groupValues?.drop(1)?.map { it.toInt() }
                ?: throw FtpException("Bad PASV reply")
            n[4] * 256 + n[5]
        }
        val d = Socket()
        d.connect(InetSocketAddress(host, port), 15_000)
        d.soTimeout = 30_000
        return d
    }

    private fun listing(cmd: String): List<String>? {
        val ds = openData()
        try {
            val (c, _) = send(cmd)
            if (c != 150 && c != 125) return null
            val lines = ds.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
            ds.close()
            val (c2, m2) = reply()
            if (c2 != 226 && c2 != 250) throw FtpException(m2)
            return lines
        } finally {
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
        try {
            val (c, m) = send("RETR ${clean(remote)}")
            if (c != 150 && c != 125) throw FtpException(m)
            ds.getInputStream().use { copy(it, out, onBytes) }
            val (c2, m2) = reply()
            if (c2 != 226 && c2 != 250) throw FtpException(m2)
        } finally {
            runCatching { ds.close() }
        }
    }

    fun upload(remote: String, input: InputStream, onBytes: (Long) -> Unit = {}) {
        val ds = openData()
        try {
            val (c, m) = send("STOR ${clean(remote)}")
            if (c != 150 && c != 125) throw FtpException(m)
            ds.getOutputStream().use { copy(input, it, onBytes) }
            val (c2, m2) = reply()
            if (c2 != 226 && c2 != 250) throw FtpException(m2)
        } finally {
            runCatching { ds.close() }
        }
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

    private fun parseMlsd(lines: List<String>): List<Entry> = lines.mapNotNull { line ->
        val sp = line.indexOf(' ')
        if (sp <= 0) return@mapNotNull null
        val facts = line.substring(0, sp).split(';').filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val name = line.substring(sp + 1)
        val type = facts["type"]?.lowercase() ?: "file"
        if (type == "cdir" || type == "pdir" || name == "." || name == "..") return@mapNotNull null
        val m = facts["modify"].orEmpty()
        val mod = if (m.length >= 12) "${m.substring(0, 4)}-${m.substring(4, 6)}-${m.substring(6, 8)} ${m.substring(8, 10)}:${m.substring(10, 12)}" else ""
        Entry(name, type == "dir", facts["size"]?.toLongOrNull() ?: 0L, mod)
    }

    private val unixLine = Regex("^([\\-dl])[rwxsStT\\-]{9}\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w{3}\\s+\\d{1,2}\\s+[\\d:]{4,5})\\s+(.+)$")

    private fun parseUnix(lines: List<String>): List<Entry> = lines.mapNotNull { line ->
        val m = unixLine.find(line.trim()) ?: return@mapNotNull null
        val name = m.groupValues[4].substringBefore(" -> ")
        if (name == "." || name == "..") return@mapNotNull null
        Entry(name, m.groupValues[1] == "d", m.groupValues[2].toLongOrNull() ?: 0L, m.groupValues[3])
    }
}
