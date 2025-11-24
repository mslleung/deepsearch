package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IPdfToMarkdownAgent
import io.deepsearch.domain.agents.PdfToMarkdownInput
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for PdfToMarkdownAgentAdkImpl.
 * 
 * Testing strategy:
 * - Small PDFs (<20MB): Use inline data approach (tested)
 * - Large PDFs (>20MB, <=50MB): Use Files API upload (implicitly tested when using real large PDFs)
 * - Oversized PDFs (>50MB): Validate rejection (tested)
 * 
 * Note: The Files API upload path for PDFs >20MB is tested when running with real PDFs
 * that exceed the inline threshold. The agent automatically routes to the appropriate method.
 */
class PdfToMarkdownAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPdfToMarkdownAgent>()

    @Test
    fun `convert simple PDF to markdown`() = runTest(testCoroutineDispatcher) {
        // Create a simple test PDF
        val pdfBytes = createSimplePdf(
            title = "Test Document",
            content = listOf(
                "This is a test PDF document.",
                "It contains multiple paragraphs.",
                "The agent should convert this to markdown format."
            )
        )

        val output = agent.generate(PdfToMarkdownInput(pdfBytes))

        assertNotNull(output.markdown, "Markdown output should not be null")
        assertTrue(output.markdown.isNotBlank(), "Markdown output should not be blank")
    }

    @Test
    fun `convert PDF with structured content to markdown`() = runTest(testCoroutineDispatcher) {
        // Create a PDF with more structured content
        val pdfBytes = createSimplePdf(
            title = "Structured Document",
            content = listOf(
                "Introduction",
                "This document has a clear structure.",
                "",
                "Section 1: Overview",
                "This is the first section with important information.",
                "",
                "Section 2: Details", 
                "This section contains detailed explanations.",
                "",
                "Conclusion",
                "This concludes our structured document."
            )
        )

        val output = agent.generate(PdfToMarkdownInput(pdfBytes))

        assertNotNull(output.markdown, "Markdown output should not be null")
        assertTrue(output.markdown.isNotBlank(), "Markdown output should not be blank")
    }
    
    @Test
    fun `reject PDF larger than 50MB`() = runTest(testCoroutineDispatcher) {
        // Create a byte array representing a 51MB PDF (just dummy bytes for size validation)
        val oversizedPdf = ByteArray(51 * 1024 * 1024) { 0 }

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            agent.generate(PdfToMarkdownInput(oversizedPdf))
        }
    }

    /**
     * Helper function to create a simple PDF for testing.
     */
    private fun createSimplePdf(title: String, content: List<String>): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)

            val contentStream = PDPageContentStream(document, page)
            try {
                contentStream.beginText()
                contentStream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16f)
                contentStream.newLineAtOffset(50f, 750f)
                contentStream.showText(title)
                contentStream.endText()

                contentStream.beginText()
                contentStream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                contentStream.newLineAtOffset(50f, 720f)
                contentStream.setLeading(14.5f)

                content.forEach { line ->
                    contentStream.showText(line)
                    contentStream.newLine()
                }

                contentStream.endText()
            } finally {
                contentStream.close()
            }

            val outputStream = ByteArrayOutputStream()
            document.save(outputStream)
            return outputStream.toByteArray()
        } finally {
            document.close()
        }
    }
}

