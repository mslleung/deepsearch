package io.deepsearch.application.services

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Result of PDF preview extraction.
 */
data class PdfPreviewResult(
    val extractedText: String,
    val pageCount: Int,
    val title: String?,
    val matchedPages: List<Int> = emptyList()
)

/**
 * Service for extracting preview text from PDF files using PDFBox.
 * 
 * This is the fast path for PDF processing - extracts text locally without
 * uploading to Gemini. Uses Lucene keyword extraction to return only relevant
 * chunks instead of the entire document.
 * 
 * Pages are scored by keyword density and only the top-K most relevant pages
 * are included in the preview.
 * 
 * The extracted text may have formatting issues (merged columns, lost table structure)
 * which the PdfSourceEvalAgent handles by filtering garbled/table facts.
 */
interface IPdfPreviewService {
    /**
     * Extract text from PDF bytes for preview evaluation.
     * 
     * Uses Lucene StandardAnalyzer to extract keywords from the query,
     * then searches PDF pages for keyword matches with context.
     * Pages are ranked by keyword density, returning only the most relevant.
     * 
     * @param pdfBytes Raw PDF file bytes
     * @param sourceUrl URL where the PDF was found (for title extraction)
     * @param query Search query for keyword extraction
     * @return PdfPreviewResult with extracted text, page count, title, and matched pages
     */
    fun extract(pdfBytes: ByteArray, sourceUrl: String, query: String): PdfPreviewResult
}

class PdfPreviewService : IPdfPreviewService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        /** Maximum number of keyword-matched pages to include */
        const val MAX_MATCHED_PAGES = 10
        
        /** Lines of context to include around keyword matches */
        const val CONTEXT_LINES = 10
        
        /** Maximum characters to return in preview (roughly 12k tokens) */
        const val MAX_PREVIEW_CHARS = 50_000
    }

    override fun extract(pdfBytes: ByteArray, sourceUrl: String, query: String): PdfPreviewResult {
        logger.debug("Extracting PDF preview from {} ({} bytes)", sourceUrl, pdfBytes.size)

        return try {
            val document = Loader.loadPDF(pdfBytes)
            document.use { pdf ->
                val pageCount = pdf.numberOfPages
                
                // Extract document title from PDF metadata if available
                val title = pdf.documentInformation?.title?.takeIf { it.isNotBlank() }
                    ?: extractTitleFromUrl(sourceUrl)

                // Extract keywords using Lucene (fast, ~1ms)
                val keywords = extractKeywords(query)
                logger.debug("Extracted keywords for PDF search: {}", keywords)

                if (keywords.isEmpty()) {
                    logger.debug("PDF preview: no keywords extracted from query, returning empty")
                    return@use PdfPreviewResult(
                        extractedText = "",
                        pageCount = pageCount,
                        title = title,
                        matchedPages = emptyList()
                    )
                }

                // Score all pages by keyword density
                val scoredPages = mutableListOf<ScoredPage>()
                
                for (pageNum in 1..pageCount) {
                    val pageStripper = PDFTextStripper().apply {
                        startPage = pageNum
                        endPage = pageNum
                    }
                    val pageText = pageStripper.getText(pdf)
                    val pageLines = pageText.lines()
                    
                    // Score: count unique keywords matched on this page
                    val pageLower = pageText.lowercase()
                    val matchedKeywords = keywords.filter { keyword -> pageLower.contains(keyword) }
                    val score = matchedKeywords.size
                    
                    if (score > 0) {
                        // Find matching lines for chunk extraction
                        val matchingLineIndices = mutableSetOf<Int>()
                        for ((lineIndex, line) in pageLines.withIndex()) {
                            val lineLower = line.lowercase()
                            if (keywords.any { keyword -> lineLower.contains(keyword) }) {
                                matchingLineIndices.add(lineIndex)
                            }
                        }
                        
                        // Expand to include context lines
                        val expandedIndices = mutableSetOf<Int>()
                        for (matchIndex in matchingLineIndices) {
                            val start = (matchIndex - CONTEXT_LINES).coerceAtLeast(0)
                            val end = (matchIndex + CONTEXT_LINES).coerceAtMost(pageLines.size - 1)
                            (start..end).forEach { expandedIndices.add(it) }
                        }
                        
                        // Build chunk from expanded indices
                        val sortedIndices = expandedIndices.sorted()
                        val chunkText = sortedIndices.joinToString("\n") { pageLines[it] }

                        scoredPages.add(ScoredPage(pageNum, chunkText, score, matchedKeywords))
                    }
                }

                if (scoredPages.isEmpty()) {
                    logger.debug("PDF preview: no pages matched keywords")
                    return@use PdfPreviewResult(
                        extractedText = "",
                        pageCount = pageCount,
                        title = title,
                        matchedPages = emptyList()
                    )
                }

                // Sort by score (descending) and take top-K pages
                val topPages = scoredPages
                    .sortedByDescending { it.score }
                    .take(MAX_MATCHED_PAGES)
                    .sortedBy { it.pageNumber } // Re-sort by page order for readability

                logger.debug(
                    "PDF keyword search: {} pages matched, taking top {} (scores: {})",
                    scoredPages.size,
                    topPages.size,
                    topPages.map { "${it.pageNumber}:${it.score}" }
                )

                // Build result from top pages
                val matchedPageNumbers = mutableSetOf<Int>()
                val combinedText = buildString {
                    var currentLength = 0
                    for (page in topPages) {
                        if (currentLength + page.chunkText.length > MAX_PREVIEW_CHARS) {
                            logger.debug("Stopping at page {} - max preview size reached", page.pageNumber)
                            break
                        }
                        append("[Page ${page.pageNumber}] (${page.score} keyword matches)\n")
                        append(page.chunkText)
                        append("\n\n")
                        matchedPageNumbers.add(page.pageNumber)
                        currentLength += page.chunkText.length
                    }
                }

                logger.debug(
                    "PDF preview for {}: {} pages total, {} pages in preview, {} chars",
                    sourceUrl, pageCount, matchedPageNumbers.size, combinedText.length
                )

                PdfPreviewResult(
                    extractedText = combinedText.take(MAX_PREVIEW_CHARS),
                    pageCount = pageCount,
                    title = title,
                    matchedPages = matchedPageNumbers.sorted()
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract PDF preview from {}: {}", sourceUrl, e.message)
            // Return empty result on failure - the full Gemini path will still run
            PdfPreviewResult(
                extractedText = "",
                pageCount = 0,
                title = extractTitleFromUrl(sourceUrl)
            )
        }
    }

    /**
     * Extract keywords from query using Lucene's StandardAnalyzer.
     * 
     * StandardAnalyzer handles:
     * - Unicode tokenization (including CJK characters)
     * - Lowercasing
     * - Stopword removal (common words in multiple languages)
     * 
     * Performance: ~1ms for typical queries (in-memory, no I/O)
     */
    private fun extractKeywords(query: String): List<String> {
        val keywords = mutableListOf<String>()
        
        StandardAnalyzer().use { analyzer ->
            analyzer.tokenStream("query", query).use { tokenStream ->
                val termAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
                tokenStream.reset()
                
                while (tokenStream.incrementToken()) {
                    val term = termAttr.toString()
                    // Filter out very short tokens (likely noise)
                    if (term.length > 2) {
                        keywords.add(term)
                    }
                }
                tokenStream.end()
            }
        }
        
        return keywords.distinct()
    }

    /**
     * Extract a reasonable title from the URL path.
     */
    private fun extractTitleFromUrl(url: String): String? {
        return try {
            val path = java.net.URI(url).path ?: return null
            val fileName = path.substringAfterLast('/')
            if (fileName.isNotBlank()) {
                // Remove .pdf extension and clean up
                fileName.removeSuffix(".pdf")
                    .removeSuffix(".PDF")
                    .replace(Regex("[_-]+"), " ")
                    .trim()
                    .takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class ScoredPage(
        val pageNumber: Int,
        val chunkText: String,
        val score: Int,
        val matchedKeywords: List<String>
    )
}
