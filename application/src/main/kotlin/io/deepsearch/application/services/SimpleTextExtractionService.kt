package io.deepsearch.application.services

import io.deepsearch.domain.services.IJsoupDomService
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Result of simple text extraction from HTML.
 */
data class SimpleTextExtractionResult(
    val text: String,
    val title: String?,
    val description: String?
)

interface ISimpleTextExtractionService {
    /**
     * Extract simple text content from HTML without any LLM processing.
     * This is a fast, programmatic extraction suitable for early evaluation
     * before full multimodal markdown is available.
     *
     * @param html The raw HTML content
     * @param url The URL of the page (for logging)
     * @return Extracted text with metadata
     */
    fun extractSimpleText(html: String, url: String): SimpleTextExtractionResult
}

class SimpleTextExtractionService(
    private val jsoupDomService: IJsoupDomService
) : ISimpleTextExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun extractSimpleText(html: String, url: String): SimpleTextExtractionResult {
        val doc = Jsoup.parse(html)
        
        val title = doc.title().takeIf { it.isNotBlank() }
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
        
        val text = jsoupDomService.extractTextContent(doc)
        
        logger.debug("Simple text extraction for {}: {} chars, title={}", url, text.length, title)
        
        return SimpleTextExtractionResult(
            text = buildSimpleMarkdown(url, title, description, text),
            title = title,
            description = description
        )
    }

    /**
     * Build a simple markdown-like format for the extracted text.
     * This format matches the structure used by WebpageExtractionService.buildMarkdown()
     * so the shortlist agent can process it consistently.
     */
    private fun buildSimpleMarkdown(
        url: String,
        title: String?,
        description: String?,
        extractedText: String
    ): String = buildString {
        appendLine("URL: $url")
        appendLine("Title: $title")
        if (!description.isNullOrBlank()) appendLine("Description: $description")
        appendLine()
        appendLine(extractedText)
    }.trim()
}
