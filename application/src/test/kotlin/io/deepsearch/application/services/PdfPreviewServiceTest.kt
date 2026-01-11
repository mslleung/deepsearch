package io.deepsearch.application.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for PdfPreviewService.
 * Tests text extraction from PDF files using PDFTextStripper with keyword-based
 * chunk extraction ranked by keyword density.
 */
class PdfPreviewServiceTest {

    private val service = PdfPreviewService()

    /**
     * Test extraction from a simple text-based PDF with keyword match.
     */
    @Test
    fun `should extract text from simple PDF with keyword match`(@TempDir tempDir: Path) {
        val pdfBytes = createSimplePdf("Hello, World! This is a test PDF document.")
        
        val result = service.extract(pdfBytes, "https://example.com/test.pdf", "test document")
        
        assertNotNull(result)
        assertTrue(result.extractedText.contains("test"), "Should extract text with keyword match")
        assertTrue(result.pageCount > 0, "Should have at least one page")
        assertTrue(result.matchedPages.isNotEmpty(), "Should have matched pages")
    }

    /**
     * Test extraction returns empty result for corrupt/invalid PDF.
     */
    @Test
    fun `should return empty result for invalid PDF`() {
        val invalidBytes = "This is not a valid PDF file".toByteArray()
        
        val result = service.extract(invalidBytes, "https://example.com/invalid.pdf", "test")
        
        assertNotNull(result)
        assertEquals("", result.extractedText, "Should return empty text for invalid PDF")
        assertEquals(0, result.pageCount, "Should return 0 pages for invalid PDF")
    }

    /**
     * Test extraction returns empty result for empty byte array.
     */
    @Test
    fun `should return empty result for empty bytes`() {
        val emptyBytes = ByteArray(0)
        
        val result = service.extract(emptyBytes, "https://example.com/empty.pdf", "test")
        
        assertNotNull(result)
        assertEquals("", result.extractedText, "Should return empty text for empty bytes")
        assertEquals(0, result.pageCount, "Should return 0 pages for empty bytes")
    }

    /**
     * Test title extraction from URL path.
     */
    @Test
    fun `should extract title from URL when PDF has no metadata title`() {
        val pdfBytes = createSimplePdf("Test content about reports")
        
        val result = service.extract(pdfBytes, "https://example.com/documents/my-report-2024.pdf", "report")
        
        assertNotNull(result)
        assertNotNull(result.title)
        assertTrue(
            result.title?.contains("report") == true || result.title?.contains("2024") == true,
            "Title should be derived from filename"
        )
    }

    /**
     * Test extraction handles PDF with multiple pages - uses keyword search.
     */
    @Test
    fun `should extract text from multi-page PDF with keyword search`() {
        val pdfBytes = createMultiPagePdf(
            listOf(
                "Page 1: Introduction to the topic.",
                "Page 2: Main content and details.",
                "Page 3: Conclusion and summary."
            )
        )
        
        val result = service.extract(pdfBytes, "https://example.com/multipage.pdf", "introduction conclusion")
        
        assertNotNull(result)
        assertTrue(result.extractedText.contains("Introduction"), "Should extract text with keyword match")
        assertTrue(result.extractedText.contains("Conclusion"), "Should extract text with keyword match")
        assertEquals(3, result.pageCount, "Should report 3 pages")
        // Only pages with keyword matches are included
        assertTrue(result.matchedPages.contains(1), "Should match page 1 (has 'introduction')")
        assertTrue(result.matchedPages.contains(3), "Should match page 3 (has 'conclusion')")
    }

    /**
     * Test keyword extraction from query using Lucene StandardAnalyzer.
     */
    @Test
    fun `should extract keywords from search query`() {
        val pdfBytes = createSimplePdf("Revenue growth in Q3 2024 was exceptional.")
        
        val result = service.extract(pdfBytes, "https://example.com/test.pdf", "What is the revenue growth?")
        
        assertNotNull(result)
        assertTrue(result.extractedText.contains("Revenue"), "Should extract PDF content with keyword match")
    }

    /**
     * Test keyword extraction handles multilingual queries.
     */
    @Test
    fun `should handle multilingual queries`() {
        val pdfBytes = createSimplePdf("This document contains important information about testing.")
        
        val result = service.extract(pdfBytes, "https://example.com/test.pdf", "testing information données")
        
        assertNotNull(result)
        assertTrue(result.extractedText.isNotEmpty(), "Should extract content")
        assertTrue(result.extractedText.contains("testing"), "Should find 'testing' keyword match")
    }

    /**
     * Test large PDF uses keyword-based extraction with ranking.
     */
    @Test
    fun `should use keyword search with ranking for large PDFs`() {
        val pages = (1..20).map { pageNum ->
            when (pageNum) {
                1 -> "Introduction: This document discusses quarterly earnings."
                2 -> "Table of contents and overview."
                3 -> "Executive summary of findings."
                10 -> "Revenue growth was 25% in Q3 2024. This is a key metric for revenue analysis."
                15 -> "The quarterly report shows strong performance."
                else -> "Page $pageNum: Lorem ipsum dolor sit amet, consectetur adipiscing elit."
            }
        }
        val pdfBytes = createMultiPagePdf(pages)
        
        val result = service.extract(pdfBytes, "https://example.com/large.pdf", "revenue growth quarterly")
        
        assertNotNull(result)
        assertEquals(20, result.pageCount, "Should report 20 pages total")
        
        // Should include pages with keyword matches
        assertTrue(result.matchedPages.contains(10), "Should match page 10 (has 'revenue growth')")
        assertTrue(result.matchedPages.contains(15), "Should match page 15 (has 'quarterly')")
        
        // Content should include the keyword matches with page markers
        assertTrue(result.extractedText.contains("revenue", ignoreCase = true), "Should extract keyword-matched content")
        assertTrue(result.extractedText.contains("[Page"), "Should have page markers")
        assertTrue(result.extractedText.contains("keyword matches"), "Should show keyword match count")
    }

    /**
     * Test that pages are ranked by keyword density.
     */
    @Test
    fun `should rank pages by keyword density`() {
        val pages = listOf(
            "This page mentions coverage once.",
            "Coverage limits and coverage amounts and coverage details are here.",
            "No relevant keywords on this page.",
            "Coverage and limits are mentioned.",
            "Another page with coverage."
        )
        val pdfBytes = createMultiPagePdf(pages)
        
        val result = service.extract(pdfBytes, "https://example.com/test.pdf", "coverage limits")
        
        assertNotNull(result)
        // Page 2 has the most keyword matches (coverage x3, limits x1 = potentially highest)
        assertTrue(result.matchedPages.contains(2), "Should include page 2 (highest keyword density)")
        // Page 3 has no matches
        assertTrue(!result.matchedPages.contains(3), "Should not include page 3 (no keywords)")
    }

    /**
     * Test that empty query returns empty result.
     */
    @Test
    fun `should return empty when query has no extractable keywords`() {
        val pages = (1..5).map { "Page content here." }
        val pdfBytes = createMultiPagePdf(pages)
        
        // Query with only stopwords
        val result = service.extract(pdfBytes, "https://example.com/test.pdf", "the a an is")
        
        assertNotNull(result)
        assertEquals(5, result.pageCount, "Should report page count")
        assertTrue(result.matchedPages.isEmpty(), "Should have no matched pages for stopword-only query")
        assertEquals("", result.extractedText, "Should return empty text for no keyword matches")
    }

    /**
     * Test MAX_MATCHED_PAGES limit.
     */
    @Test
    fun `should limit to MAX_MATCHED_PAGES`() {
        // Create 20 pages that all match
        val pages = (1..20).map { "Page $it: This contains the keyword test." }
        val pdfBytes = createMultiPagePdf(pages)
        
        val result = service.extract(pdfBytes, "https://example.com/test.pdf", "keyword test")
        
        assertNotNull(result)
        assertEquals(20, result.pageCount, "Should report 20 pages total")
        assertTrue(
            result.matchedPages.size <= PdfPreviewService.MAX_MATCHED_PAGES,
            "Should not exceed MAX_MATCHED_PAGES (${PdfPreviewService.MAX_MATCHED_PAGES})"
        )
    }

    /**
     * Helper to create a minimal valid PDF with text content.
     */
    private fun createSimplePdf(text: String): ByteArray {
        val document = org.apache.pdfbox.pdmodel.PDDocument()
        try {
            val page = org.apache.pdfbox.pdmodel.PDPage()
            document.addPage(page)
            
            val font = org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA
            )
            
            val contentStream = org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)
            contentStream.beginText()
            contentStream.setFont(font, 12f)
            contentStream.newLineAtOffset(50f, 700f)
            contentStream.showText(text)
            contentStream.endText()
            contentStream.close()
            
            val outputStream = java.io.ByteArrayOutputStream()
            document.save(outputStream)
            return outputStream.toByteArray()
        } finally {
            document.close()
        }
    }

    /**
     * Helper to create a multi-page PDF with text content.
     */
    private fun createMultiPagePdf(pageTexts: List<String>): ByteArray {
        val document = org.apache.pdfbox.pdmodel.PDDocument()
        try {
            val font = org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA
            )
            
            for (text in pageTexts) {
                val page = org.apache.pdfbox.pdmodel.PDPage()
                document.addPage(page)
                
                val contentStream = org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)
                contentStream.beginText()
                contentStream.setFont(font, 12f)
                contentStream.newLineAtOffset(50f, 700f)
                contentStream.showText(text)
                contentStream.endText()
                contentStream.close()
            }
            
            val outputStream = java.io.ByteArrayOutputStream()
            document.save(outputStream)
            return outputStream.toByteArray()
        } finally {
            document.close()
        }
    }
}
