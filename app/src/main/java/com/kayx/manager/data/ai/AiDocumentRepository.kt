package com.kayx.manager.data.ai

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.CodingErrorAction

data class AiDocumentChunk(
    val documentName: String,
    val index: Int,
    val text: String
)

data class AiDocumentLoadResult(
    val documentName: String,
    val chunks: List<AiDocumentChunk>,
    val characterCount: Int
)

sealed interface AiDocumentResult {
    data class Success(val document: AiDocumentLoadResult) : AiDocumentResult
    data class Unsupported(val reason: String) : AiDocumentResult
    data class Failure(val reason: String) : AiDocumentResult
}

/** Bounded, memory-only document ingestion. It never uploads or persists document text. */
class AiDocumentRepository(
    private val resolver: ContentResolver
) {
    fun load(uri: Uri, displayName: String, mimeType: String?): AiDocumentResult {
        if (!isSupported(displayName, mimeType)) {
            return AiDocumentResult.Unsupported("This file type is not supported for text extraction")
        }
        return try {
            val stream = resolver.openInputStream(uri)
                ?: return AiDocumentResult.Failure("Could not open the selected document")
            stream.use { input ->
                val bytes = readBounded(input, MAX_DOCUMENT_BYTES)
                    ?: return AiDocumentResult.Failure("Document exceeds the 2 MB processing limit")
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                    .getOrElse { return AiDocumentResult.Unsupported("Only valid UTF-8 text documents are supported") }
                if ('\u0000' in text) return AiDocumentResult.Unsupported("Binary documents are not supported")
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                    .filter { it == '\n' || it == '\t' || it >= ' ' }
                    .trim()
                if (normalized.isBlank()) return AiDocumentResult.Failure("The selected document contains no readable text")
                if (normalized.length > MAX_DOCUMENT_CHARS) {
                    return AiDocumentResult.Failure("Document exceeds the 1,000,000 character processing limit")
                }
                AiDocumentResult.Success(
                    AiDocumentLoadResult(displayName.take(256), chunk(displayName, normalized), normalized.length)
                )
            }
        } catch (_: SecurityException) {
            AiDocumentResult.Failure("KayX no longer has permission to read this document")
        } catch (_: Exception) {
            AiDocumentResult.Failure("Could not read the selected document")
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun chunk(name: String, text: String): List<AiDocumentChunk> {
        val chunks = mutableListOf<AiDocumentChunk>()
        var start = 0
        while (start < text.length && chunks.size < MAX_CHUNKS) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf('\n', end).takeIf { it > start + CHUNK_SIZE / 2 }
                if (boundary != null) end = boundary
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) chunks += AiDocumentChunk(name.take(256), chunks.size, part)
            if (end >= text.length) break
            start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
        }
        return chunks
    }

    private fun isSupported(name: String, mime: String?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        val allowedExtensions = setOf("txt", "md", "markdown", "csv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts", "css", "yaml", "yml", "toml", "ini", "properties", "sh", "rs", "c", "cpp", "h")
        val allowedMime = mime?.lowercase()?.let {
            it.startsWith("text/") || it in setOf("application/json", "application/xml", "application/yaml", "application/x-yaml")
        } == true
        return extension in allowedExtensions || allowedMime && extension !in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
        const val MAX_DOCUMENT_CHARS = 120_000
        const val MAX_CHUNKS = 128
        const val CHUNK_SIZE = 1_200
        const val CHUNK_OVERLAP = 160
    }
}

/** Deterministic local lexical retrieval; no embeddings or network are involved. */
object AiLocalRetriever {
    fun search(chunks: List<AiDocumentChunk>, query: String, limit: Int = 5): List<AiDocumentChunk> {
        val terms = tokenize(query)
        if (terms.isEmpty() || limit <= 0) return emptyList()
        return chunks.mapIndexed { index, chunk ->
            val contentTerms = tokenize(chunk.text)
            val score = terms.sumOf { term -> contentTerms.count { it == term }.coerceAtMost(4) }
            Triple(index, score, chunk)
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Int, Int, AiDocumentChunk>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.third }
    }

    private fun tokenize(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]{2,}").findAll(value.lowercase()).map { it.value }.toList()
}

/** The only path that can produce document text for a remote request requires explicit consent. */
object AiDocumentShareGate {
    fun contextForRemoteRequest(chunks: List<AiDocumentChunk>, consentGranted: Boolean): String {
        require(consentGranted) { "Explicit document-sharing consent is required" }
        return chunks.joinToString("\n\n") { "[Source: ${it.documentName} · chunk ${it.index + 1}]\n${it.text}" }
            .take(MAX_SHARED_CONTEXT_CHARS)
    }

    const val MAX_SHARED_CONTEXT_CHARS = 24_000
}
