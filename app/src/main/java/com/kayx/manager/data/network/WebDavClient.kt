package com.kayx.manager.data.network

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Minimal HTTPS-only WebDAV client. Redirects are rejected so Basic credentials can never
 * be forwarded to another origin. The platform TLS stack verifies certificates/hostnames.
 */
class WebDavClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) {
    data class Entry(val name: String, val href: String, val directory: Boolean, val size: Long, val modified: String)

    private var baseUri: URI? = null
    private var authorization: String? = null
    @Volatile private var active: HttpURLConnection? = null

    fun connect(serverUrl: String, username: String, password: String) {
        baseUri = validateWebDavServerUrl(serverUrl)
        authorization = "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun disconnect() {
        active?.disconnect()
        active = null
        authorization = null
        baseUri = null
    }

    fun cancelRequest() {
        active?.disconnect()
        active = null
    }

    fun list(path: String = ""): List<Entry> {
        val url = resolve(path)
        val connection = open(url, "PROPFIND")
        try {
            connection.setRequestProperty("Depth", "1")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(PROPFIND_BODY) }
            val code = connection.responseCode
            if (code != 207) throw WebDavException("WebDAV PROPFIND failed: HTTP $code")
            val xml = connection.inputStream.use { readBounded(it, MAX_XML_BYTES) }
            return parseMultistatus(String(xml, StandardCharsets.UTF_8), url)
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }

    fun download(path: String, output: OutputStream, onBytes: (Long) -> Unit = {}) {
        transfer("GET", resolve(path), null, output, onBytes)
    }

    fun upload(path: String, input: InputStream, onBytes: (Long) -> Unit = {}) {
        transfer("PUT", resolve(path), input, null, onBytes)
    }

    /**
     * Resume a download only when HEAD supplies a stable validator and a known length.
     * A partial response must be 206 with a Content-Range starting at [offset].
     * If the server ignores Range or the resource changed, no response bytes are appended.
     */
    fun downloadResumable(path: String, output: OutputStream, offset: Long, onBytes: (Long) -> Unit = {}): Long {
        val url = resolve(path)
        val metadataConnection = open(url, "HEAD")
        val totalLength: Long
        val etag: String?
        val lastModified: String?
        try {
            val code = metadataConnection.responseCode
            if (code !in 200..299) throw WebDavException("WebDAV HEAD failed: HTTP $code")
            totalLength = metadataConnection.getHeaderFieldLong("Content-Length", -1L)
            etag = metadataConnection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
            lastModified = metadataConnection.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            metadataConnection.disconnect()
            if (active === metadataConnection) active = null
        }
        if (totalLength < 0L) throw WebDavException("WebDAV server did not provide a verifiable Content-Length")
        validateWebDavResumeOffset(offset, totalLength)
        if (offset == totalLength) return totalLength

        val strongEtag = etag?.takeIf(::isStrongWebDavEtag)
        val validator = strongEtag ?: lastModified
        if (validator == null) {
            throw WebDavException("WebDAV resume requires a strong ETag or Last-Modified validator")
        }

        val connection = open(url, "GET")
        try {
            if (offset > 0L) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                connection.setRequestProperty("If-Range", validator ?: throw WebDavException("Missing WebDAV resume validator"))
            } else {
                if (strongEtag != null) connection.setRequestProperty("If-Match", strongEtag)
                else if (lastModified != null) connection.setRequestProperty("If-Unmodified-Since", lastModified)
            }
            val code = connection.responseCode
            if (offset > 0L) {
                if (code != 206) throw WebDavException("WebDAV server did not honor the resume range (HTTP $code)")
                val range = parseWebDavContentRange(connection.getHeaderField("Content-Range"))
                    ?: throw WebDavException("WebDAV server returned an invalid Content-Range")
                if (range.start != offset || range.total != totalLength || range.endInclusive != totalLength - 1L) {
                    throw WebDavException("WebDAV resource changed or returned a mismatched range")
                }
            } else if (code != 200) {
                throw WebDavException("WebDAV download failed: HTTP $code")
            }

            val responseEtag = connection.getHeaderField("ETag")?.trim()
            val responseLastModified = connection.getHeaderField("Last-Modified")?.trim()
            if (strongEtag != null && responseEtag != strongEtag) {
                throw WebDavException("WebDAV ETag was missing or changed during download")
            }
            if (strongEtag == null && responseLastModified != lastModified) {
                throw WebDavException("WebDAV Last-Modified validator was missing or changed during download")
            }
            val expectedBytes = totalLength - offset
            var transferred = 0L
            connection.inputStream.use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (transferred > expectedBytes - count) {
                        throw WebDavException("WebDAV response exceeded the verified file length")
                    }
                    output.write(buffer, 0, count)
                    transferred += count
                    onBytes(offset + transferred)
                }
            }
            if (transferred != expectedBytes) {
                throw WebDavException("WebDAV response length did not match the verified file size")
            }
            output.flush()
            return totalLength
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }

    fun createDirectory(path: String) = execute("MKCOL", resolve(path), setOf(201, 200, 405))
    fun delete(path: String) = execute("DELETE", resolve(path), setOf(200, 202, 204))
    fun move(source: String, destination: String, overwrite: Boolean = false) {
        val sourceUri = resolve(source)
        val targetUri = resolve(destination)
        val connection = open(sourceUri, "MOVE")
        try {
            connection.setRequestProperty("Destination", targetUri.toExternalForm())
            connection.setRequestProperty("Overwrite", if (overwrite) "T" else "F")
            val code = connection.responseCode
            if (code !in setOf(201, 204)) throw WebDavException("WebDAV MOVE failed: HTTP $code")
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }

    private fun transfer(
        method: String,
        url: URL,
        input: InputStream?,
        output: OutputStream?,
        onBytes: (Long) -> Unit
    ) {
        val connection = open(url, method)
        try {
            if (input != null) {
                connection.doOutput = true
                connection.setChunkedStreamingMode(64 * 1024)
                connection.outputStream.use { out -> copy(input, out, onBytes) }
            } else {
                val code = connection.responseCode
                if (code !in 200..299) throw WebDavException("WebDAV $method failed: HTTP $code")
                connection.inputStream.use { source -> copy(source, output!!, onBytes) }
            }
            if (input != null) {
                val code = connection.responseCode
                if (code !in setOf(200, 201, 204)) throw WebDavException("WebDAV PUT failed: HTTP $code")
            }
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }

    private fun execute(method: String, url: URL, accepted: Set<Int>) {
        val connection = open(url, method)
        try {
            val code = connection.responseCode
            if (code !in accepted) throw WebDavException("WebDAV $method failed: HTTP $code")
        } finally {
            connection.disconnect()
            if (active === connection) active = null
        }
    }

    private fun open(url: URL, method: String): HttpURLConnection {
        require(url.protocol.equals("https", ignoreCase = true)) { "WebDAV requests must use HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", authorization ?: throw IllegalStateException("Not connected"))
        connection.setRequestProperty("User-Agent", "KayX-Android")
        active = connection
        return connection
    }

    private fun resolve(path: String): URL {
        val root = baseUri ?: throw IllegalStateException("Not connected")
        require(path.length <= MAX_PATH_LENGTH && !path.startsWith("/") && !path.contains('\\') &&
            path.split('/').none { it == ".." || it == "." }) { "Invalid WebDAV relative path" }
        val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        val rootPath = if (root.path.endsWith("/")) root.path else root.path + "/"
        return URL(URI(root.scheme, null, root.host, root.port, rootPath + encoded, root.query, null).toString())
    }

    internal fun parseMultistatusForTest(xml: String, requestUrl: URL): List<Entry> = parseMultistatus(xml, requestUrl)

    private fun parseMultistatus(xml: String, requestUrl: URL): List<Entry> {
        require(xml.length <= MAX_XML_BYTES) { "WebDAV response is too large" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
        val responses = document.getElementsByTagNameNS("*", "response")
        val requestPath = URI(requestUrl.toString()).normalize().path.trimEnd('/')
        return (0 until responses.length).mapNotNull { index ->
            val response = responses.item(index) as? Element ?: return@mapNotNull null
            val href = response.firstText("href") ?: return@mapNotNull null
            val resolved = requestUrl.toURI().resolve(href).normalize()
            require(resolved.scheme == "https" && resolved.host.equals(requestUrl.host, true) && resolved.port == requestUrl.port) {
                "WebDAV response contains a cross-origin href"
            }
            val remotePath = resolved.path.trimEnd('/')
            if (remotePath == requestPath) return@mapNotNull null
            val rawName = remotePath.substringAfterLast('/')
            val name = java.net.URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8")
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return@mapNotNull null
            val prop = response.firstElement("prop")
            val resourceType = prop?.firstElement("resourcetype")
            val directory = resourceType?.getElementsByTagNameNS("*", "collection")?.length?.let { it > 0 } ?: false
            val size = prop?.firstText("getcontentlength")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val modified = prop?.firstText("getlastmodified").orEmpty()
            Entry(name, remotePath, directory, size, modified)
        }.take(MAX_ENTRIES)
    }

    private fun Element.firstElement(localName: String): Element? =
        getElementsByTagNameNS("*", localName).item(0) as? Element

    private fun Element.firstText(localName: String): String? =
        firstElement(localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

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

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw WebDavException("WebDAV XML response exceeds the safety limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    class WebDavException(message: String) : Exception(message)

    private companion object {
        const val MAX_PATH_LENGTH = 4096
        const val MAX_XML_BYTES = 2 * 1024 * 1024
        const val MAX_ENTRIES = 5000
        val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>""".toByteArray(StandardCharsets.UTF_8)
    }
}



internal data class WebDavContentRange(val start: Long, val endInclusive: Long, val total: Long)

internal fun parseWebDavContentRange(value: String?): WebDavContentRange? {
    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0L || end < start || total <= end) return null
    return WebDavContentRange(start, end, total)
}

internal fun validateWebDavResumeOffset(offset: Long, totalLength: Long): Long {
    require(totalLength >= 0L) { "Remote file size is invalid" }
    require(offset >= 0L && offset <= totalLength) { "Resume offset is outside the current remote file" }
    return offset
}

internal fun isStrongWebDavEtag(value: String?): Boolean =
    !value.isNullOrBlank() && !value.trim().startsWith("W/", ignoreCase = true)

internal fun validateWebDavServerUrl(serverUrl: String): URI {
    val parsed = runCatching { URI(serverUrl.trim()) }.getOrElse { throw IllegalArgumentException("Invalid WebDAV URL", it) }
    require(parsed.scheme.equals("https", ignoreCase = true)) { "WebDAV requires HTTPS" }
    require(!parsed.host.isNullOrBlank()) { "WebDAV host is required" }
    require(parsed.userInfo == null && parsed.fragment == null) { "Do not embed credentials or fragments in the WebDAV URL" }
    require(parsed.port in -1..65535) { "Invalid WebDAV port" }
    return if (parsed.path.isNullOrBlank()) URI(parsed.scheme, null, parsed.host, parsed.port, "/", parsed.query, null) else parsed
}
