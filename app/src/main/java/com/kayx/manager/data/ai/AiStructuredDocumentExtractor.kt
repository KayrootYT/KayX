package com.kayx.manager.data.ai

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream\nimport java.io.Writer
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Local-only extractors for PDF and OOXML Office files.
 * Input and expanded archive data are bounded; no extracted text is persisted or uploaded here.
 */
internal object AiStructuredDocumentExtractor {
    fun extractPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            require(!document.isEncrypted) { "Encrypted PDFs are not supported" }
            require(document.numberOfPages in 1..MAX_PDF_PAGES) { "PDF page count exceeds the processing limit" }
            val bounded = BoundedTextWriter(MAX_EXTRACTED_CHARS)
            PDFTextStripper().apply {
                startPage = 1
                endPage = document.numberOfPages
            }.writeText(document, bounded)
            return normalize(bounded.value())
        }
    }

    fun extractOffice(bytes: ByteArray, extension: String): String {
        val entries = readZipEntries(bytes)
        require("[Content_Types].xml" in entries) { "Invalid Office Open XML document" }
        val text = when (extension) {
            "docx" -> {
                val xml = entries["word/document.xml"] ?: error("Word document content is missing")
                elementsText(xml, "t")
            }
            "pptx" -> {
                val slides = entries.filterKeys { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
                require(slides.isNotEmpty()) { "Presentation contains no readable slides" }
                slides.toSortedMap().values.joinToString("\n") { elementsText(it, "t") }
            }
            "xlsx" -> extractSpreadsheet(entries)
            else -> throw IllegalArgumentException("Unsupported Office format")
        }
        return normalize(text)
    }


    private class BoundedTextWriter(private val maxChars: Int) : Writer() {
        private val content = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (content.length + length > maxChars) {
                throw java.io.IOException("Extracted PDF text exceeds the processing limit")
            }
            content.append(chars, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        fun value(): String = content.toString()
    }
\n    private fun extractSpreadsheet(entries: Map<String, ByteArray>): String {
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            parseXml(xml).getElementsByTagNameNS("*", "si").let { nodes ->
                (0 until nodes.length).map { index ->
                    val item = nodes.item(index) as Element
                    descendantsText(item, "t")
                }
            }
        }.orEmpty()
        val sheets = entries.filterKeys { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }
        require(sheets.isNotEmpty()) { "Workbook contains no readable worksheets" }
        return sheets.toSortedMap().values.joinToString("\n") { xml ->
            val doc = parseXml(xml)
            val cells = doc.getElementsByTagNameNS("*", "c")
            (0 until cells.length).mapNotNull { index ->
                val cell = cells.item(index) as Element
                val value = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                when (cell.getAttribute("t")) {
                    "s" -> value.toIntOrNull()?.let(shared::getOrNull)
                    "inlineStr" -> descendantsText(cell, "t")
                    "b", "str", "e" -> value
                    else -> value.takeIf { it.isNotBlank() }
                }
            }.joinToString(" ")
        }
    }

    private fun elementsText(xml: ByteArray, localName: String): String {
        val doc = parseXml(xml)
        val nodes = doc.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).joinToString(" ") { nodes.item(it).textContent.orEmpty() }
    }

    private fun descendantsText(element: Element, localName: String): String {
        val nodes = element.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent.orEmpty() }
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setXIncludeAware(false)
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(bytes.toString(Charsets.UTF_8))))
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ZIP_ENTRIES) { "Office archive contains too many entries" }
                val name = entry.name
                require(!name.startsWith("/") && name.split('/').none { it == ".." }) { "Invalid Office archive path" }
                if (!entry.isDirectory) {
                    val selected = name == "[Content_Types].xml" || name == "word/document.xml" ||
                        name == "xl/sharedStrings.xml" || name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) ||
                        name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))
                    val out = if (selected) ByteArrayOutputStream() else null
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_EXPANDED_BYTES) { "Office archive expands beyond the 2 MB processing limit" }
                        out?.write(buffer, 0, n)
                    }
                    if (out != null) result[name] = out.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun normalize(value: String): String = value
        .replace("\r\n", "\n").replace('\r', '\n')
        .filter { it == '\n' || it == '\t' || it >= ' ' }
        .trim()

    const val MAX_EXTRACTED_CHARS = 120_000\n    const val MAX_PDF_PAGES = 200
    const val MAX_ZIP_ENTRIES = 256
    const val MAX_EXPANDED_BYTES = 2 * 1024 * 1024
}
