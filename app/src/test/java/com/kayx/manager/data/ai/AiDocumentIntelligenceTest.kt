package com.kayx.manager.data.ai

import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class AiDocumentIntelligenceTest {
    private val chunks = listOf(
        AiDocumentChunk("notes.txt", 0, "KayX uses an offline first file manager architecture."),
        AiDocumentChunk("guide.md", 1, "RAG retrieves relevant document chunks before an AI request."),
        AiDocumentChunk("notes.txt", 1, "Local search keeps document content on the Android device.")
    )

    @Test fun retrievesRelevantChunksLocally() {
        val result = AiLocalRetriever.search(chunks, "document chunks", 5)
        assertEquals(1, result.size)
        assertEquals("guide.md", result.first().documentName)
    }

    @Test fun emptyQueryReturnsNoResults() {
        assertTrue(AiLocalRetriever.search(chunks, "   ").isEmpty())
    }

    @Test fun resultLimitIsRespected() {
        assertEquals(1, AiLocalRetriever.search(chunks, "local document", 1).size)
        assertTrue(AiLocalRetriever.search(chunks, "local document", 0).isEmpty())
    }

    @Test fun remoteContextRequiresExplicitConsent() {
        try {
            AiDocumentShareGate.contextForRemoteRequest(chunks, false)
            fail("Context must not be exported without explicit consent")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed behavior.
        }
    }

    @Test fun consentedContextIncludesSourceReferences() {
        val context = AiDocumentShareGate.contextForRemoteRequest(chunks.take(1), true)
        assertTrue(context.contains("[Source: notes.txt"))
        assertTrue(context.contains("offline first file manager"))
    }

    @Test fun remotePromptCannotBeBuiltWithoutConsent() {
        try {
            AiDocumentPromptBuilder.build("Summarize this", chunks.take(1), consentGranted = false)
            fail("Prompt must not be built without explicit consent")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed behavior.
        }
    }

    @Test fun remotePromptFramesDocumentTextAsUntrustedAndKeepsSources() {
        val messages = AiDocumentPromptBuilder.build("What is KayX?", chunks.take(1), consentGranted = true)
        assertEquals(2, messages.size)
        assertEquals(AiChatMessage.Role.SYSTEM, messages[0].role)
        assertTrue(messages[0].content.contains("untrusted data"))
        assertTrue(messages[1].content.contains("Question: What is KayX?"))
        assertTrue(messages[1].content.contains("[Source: notes.txt"))
    }

    @Test fun remotePromptRejectsBlankQuestion() {
        try {
            AiDocumentPromptBuilder.build("  ", chunks.take(1), consentGranted = true)
            fail("A non-blank question is required")
        } catch (_: IllegalArgumentException) {
            // Expected validation.
        }
    }

    @Test fun remoteContextIsBounded() {
        val huge = listOf(AiDocumentChunk("large.txt", 0, "x".repeat(30_000)))
        val context = AiDocumentShareGate.contextForRemoteRequest(huge, true)
        assertEquals(AiDocumentShareGate.MAX_SHARED_CONTEXT_CHARS, context.length)
    }

    @Test fun extractsWordDocumentTextFromDocx() {
        val bytes = officeZip(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>KayX offline document</w:t></w:r></w:p></w:body></w:document>"""
        )
        assertTrue(AiStructuredDocumentExtractor.extractOffice(bytes, "docx").contains("KayX offline document"))
    }

    @Test fun extractsPresentationSlideTextFromPptx() {
        val bytes = officeZip(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="p" xmlns:a="a"><a:t>Quarterly results</a:t></p:sld>"""
        )
        assertTrue(AiStructuredDocumentExtractor.extractOffice(bytes, "pptx").contains("Quarterly results"))
    }

    @Test fun extractsSharedAndInlineCellTextFromXlsx() {
        val bytes = officeZip(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Revenue</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row><c t="s"><v>0</v></c><c t="inlineStr"><is><t>2026</t></is></c><c><v>1200</v></c></row></sheetData></worksheet>"""
        )
        val text = AiStructuredDocumentExtractor.extractOffice(bytes, "xlsx")
        assertTrue(text.contains("Revenue"))
        assertTrue(text.contains("2026"))
        assertTrue(text.contains("1200"))
    }

    @Test fun officeXmlRejectsDoctypeAndExternalEntities() {
        val bytes = officeZip(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///etc/passwd">]><x>&ext;</x>"""
        )
        try {
            AiStructuredDocumentExtractor.extractOffice(bytes, "docx")
            fail("Office XML must reject DTD and external entities")
        } catch (_: Exception) {
            // Expected secure XML parser rejection.
        }
    }

    private fun officeZip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

}
