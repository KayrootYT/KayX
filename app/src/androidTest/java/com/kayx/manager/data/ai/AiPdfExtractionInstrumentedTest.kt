package com.kayx.manager.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android because PDFBox Android needs the application asset/resource environment. */
@RunWith(AndroidJUnit4::class)
class AiPdfExtractionInstrumentedTest {
    @Test
    fun extractsTextFromNormalPdf() {
        val extracted = AiStructuredDocumentExtractor.extractPdf(appContext, createPdf("KayX PDF fixture text"))
        assertTrue("Expected the PDF text to be extracted, got: $extracted", extracted.contains("KayX PDF fixture text"))
    }

    @Test
    fun rejectsPasswordProtectedPdf() {
        val encrypted = createPdf("Secret PDF text", encrypt = true)
        try {
            AiStructuredDocumentExtractor.extractPdf(appContext, encrypted)
            fail("Password-protected PDF must be rejected without prompting or guessing a password")
        } catch (_: Exception) {
            // Expected: PDFBox must not open an encrypted document without a password.
        }
    }

    @Test
    fun scannedImageOnlyStylePdfProducesNoExtractedText() {
        val bytes = createPdf(text = null)
        assertTrue(AiStructuredDocumentExtractor.extractPdf(appContext, bytes).isBlank())
    }

    @Test
    fun rejectsPdfAbovePageLimit() {
        val bytes = ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                repeat(AiStructuredDocumentExtractor.MAX_PDF_PAGES + 1) { document.addPage(PDPage()) }
                document.save(output)
            }
            output.toByteArray()
        }
        try {
            AiStructuredDocumentExtractor.extractPdf(appContext, bytes)
            fail("PDF above the configured page limit must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("page count"))
        }
    }

    private fun createPdf(text: String?, encrypt: Boolean = false): ByteArray {
        return ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                val page = PDPage()
                document.addPage(page)
                if (text != null) {
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(48f, 720f)
                        stream.showText(text)
                        stream.endText()
                    }
                }
                if (encrypt) {
                    val policy = StandardProtectionPolicy(
                        "kayx-test-owner",
                        "kayx-test-user",
                        AccessPermission()
                    ).apply { encryptionKeyLength = 128 }
                    document.protect(policy)
                }
                document.save(output)
            }
            output.toByteArray()
        }
    }

    companion object {
        private val appContext by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        }

        @JvmStatic
        @BeforeClass
        fun initializePdfBox() {
            PDFBoxResourceLoader.init(appContext)
        }
    }
}
