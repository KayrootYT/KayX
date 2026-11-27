package com.kayx.manager.data.ai

import org.junit.Assert.*
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
}
