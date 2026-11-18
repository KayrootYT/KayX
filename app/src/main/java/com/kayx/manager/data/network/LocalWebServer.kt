package com.kayx.manager.data.network

import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny static-file HTTP server bound to the loopback interface only. It lets the HTML preview use a chosen
 * "website root directory", so root-relative links (/css/app.css), fetch(), ES modules and video seeking
 * (Range requests) behave like on a real web server.
 */
class LocalWebServer(root: File) {
    private val rootDir: File = root.canonicalFile
    private var server: ServerSocket? = null

    /** Starts listening on a free port and returns it. */
    fun start(): Int {
        val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true, name = "kayx-web") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true, name = "kayx-web-conn") { runCatching { handle(c) } }
            }
        }
        return s.localPort
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun mimeOf(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "html", "htm", "xhtml" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "txt", "md", "log" -> "text/plain; charset=utf-8"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun handle(sock: Socket) = sock.use { c ->
        c.soTimeout = 15_000
        val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val reqLine = inp.readLine() ?: return@use
        var range: String? = null
        while (true) {
            val l = inp.readLine() ?: break
            if (l.isEmpty()) break
            if (l.startsWith("range:", ignoreCase = true)) range = l.substringAfter(':').trim()
        }
        val parts = reqLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val target = parts.getOrNull(1) ?: "/"
        val out = c.getOutputStream()
        fun reply(code: Int, text: String, body: String = text) {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            if (method != "HEAD") out.write(b)
            out.flush()
        }
        if (method != "GET" && method != "HEAD") return@use reply(405, "Method Not Allowed")
        var f = File(rootDir, Uri.decode(target.substringBefore('?').substringBefore('#')).trimStart('/')).canonicalFile
        if (f.path != rootDir.path && !f.path.startsWith(rootDir.path + File.separator)) return@use reply(403, "Forbidden")
        if (f.isDirectory) f = File(f, "index.html")
        if (!f.isFile) return@use reply(404, "Not Found")

        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        range?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.substringBefore(',')?.let { r ->
            val a = r.substringBefore('-').trim(); val b = r.substringAfter('-', "").trim()
            if (a.isEmpty()) { b.toLongOrNull()?.let { start = (len - it).coerceAtLeast(0); partial = true } }
            else a.toLongOrNull()?.let { start = it; end = b.toLongOrNull()?.coerceAtMost(len - 1) ?: (len - 1); partial = true }
        }
        if (partial && (start > end || start >= len)) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush(); return@use
        }
        val count = if (len == 0L) 0L else end - start + 1
        val head = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ").append(mimeOf(f)).append("\r\n")
            .append("Content-Length: ").append(count).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Cache-Control: no-cache\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
        if (partial) head.append("Content-Range: bytes $start-$end/$len\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (method == "GET" && count > 0) RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n); left -= n
            }
        }
        out.flush()
    }
}
