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
 * 
 * Configured for RAG-friendly output:
 * - SKIP_ATTRIBUTES: Prevents Pandoc-style IDs {#...} from being added (CMS widget IDs, etc.)
 * - SKIP_CHAR_ESCAPE: Prevents unnecessary escaping of &, <, > for cleaner text
 * 
 * Falls back to post-processing for any edge cases the configuration doesn't catch.
 */
class HtmlToMarkdownService : IHtmlToMarkdownService {
    
    private val converter: FlexmarkHtmlConverter

    init {
        val options = MutableDataSet().apply {
            // Skip attribute conversion (e.g., {#id}, {.class}) - not useful for RAG
            set(FlexmarkHtmlConverter.SKIP_ATTRIBUTES, true)
            // Skip escaping special characters - we want clean readable text for RAG
            set(FlexmarkHtmlConverter.SKIP_CHAR_ESCAPE, true)
        }
        converter = FlexmarkHtmlConverter.builder(options).build()
    }
    
    override fun convert(html: String): String {
        return converter.convert(html)
    }
}
