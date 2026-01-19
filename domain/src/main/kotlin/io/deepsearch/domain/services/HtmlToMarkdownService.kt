package io.deepsearch.domain.services

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.util.data.MutableDataSet

/**
 * Service for converting HTML to Markdown.
 * Uses flexmark-java's HTML-to-Markdown converter to preserve document structure
 * (headings, lists, paragraphs, links) during conversion.
 */
interface IHtmlToMarkdownService {
    /**
     * Converts HTML content to Markdown.
     * Preserves structural elements like headings, lists, paragraphs, and links.
     * 
     * @param html The HTML content to convert
     * @return Markdown representation of the HTML
     */
    fun convert(html: String): String
}

/**
 * Implementation using flexmark-java's HTML-to-Markdown converter.
 */
class HtmlToMarkdownService : IHtmlToMarkdownService {
    
    private val converter: FlexmarkHtmlConverter
    
    init {
        val options = MutableDataSet()
        // Default options work well for most cases
        // Additional configuration can be added here if needed
        converter = FlexmarkHtmlConverter.builder(options).build()
    }
    
    override fun convert(html: String): String {
        return converter.convert(html)
    }
}
