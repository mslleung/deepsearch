package io.deepsearch.application.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Result of HTML preview preparation.
 */
data class HtmlPreviewResult(
    val cleanedHtml: String,
    val title: String?,
    val description: String?
)

interface IHtmlPreviewService {
    /**
     * Prepare a cleaned HTML preview for the preview shortlist agent.
     * 
     * Aggressive cleaning strategy:
     * - Remove: script, style, noscript, link, meta, svg, iframe
     * - Remove: nav, header, footer, aside (navigation elements)
     * - Strip most attributes except semantic ones: class, id, role, aria-label
     * - Preserve semantic structure: article, section, main, p, h1-h6, ul, ol, li
     * 
     * @param html The raw HTML content
     * @param url The URL of the page (for logging)
     * @return Cleaned HTML with metadata
     */
    fun prepareHtmlPreview(html: String, url: String): HtmlPreviewResult
}

class HtmlPreviewService : IHtmlPreviewService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // Elements to completely remove
        private val REMOVE_ELEMENTS = setOf(
            "script", "style", "noscript", "link", "meta", "svg", "iframe",
            "nav", "header", "footer", "aside", "form", "input", "button",
            "select", "textarea", "canvas", "video", "audio", "object", "embed"
        )

        // Attributes to keep (all others are stripped)
        private val KEEP_ATTRIBUTES = setOf(
            "class", "id", "role", "aria-label", "aria-labelledby",
            "href", "src", "alt", "title", "data-testid"
        )
    }

    override fun prepareHtmlPreview(html: String, url: String): HtmlPreviewResult {
        val doc = Jsoup.parse(html)

        val title = doc.title().takeIf { it.isNotBlank() }
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }

        // Remove unwanted elements
        removeUnwantedElements(doc)

        // Strip unnecessary attributes
        stripAttributes(doc)

        // Remove empty elements
        removeEmptyElements(doc)

        // Get the cleaned body HTML
        val cleanedHtml = doc.body().html()

        logger.debug("HTML preview for {}: {} -> {} chars", url, html.length, cleanedHtml.length)

        return HtmlPreviewResult(
            cleanedHtml = cleanedHtml,
            title = title,
            description = description
        )
    }

    private fun removeUnwantedElements(doc: Document) {
        REMOVE_ELEMENTS.forEach { tag ->
            doc.select(tag).remove()
        }

        // Also remove comments
        doc.select("*").forEach { element ->
            element.childNodes()
                .filter { it.nodeName() == "#comment" }
                .forEach { it.remove() }
        }

        // Remove hidden elements
        doc.select("[hidden], [style*='display: none'], [style*='display:none']").remove()
        doc.select("[aria-hidden='true']").remove()
    }

    private fun stripAttributes(doc: Document) {
        doc.allElements.forEach { element ->
            val attributesToRemove = element.attributes()
                .filter { attr -> attr.key.lowercase() !in KEEP_ATTRIBUTES }
                .map { it.key }

            attributesToRemove.forEach { attr ->
                element.removeAttr(attr)
            }
        }
    }

    private fun removeEmptyElements(doc: Document) {
        // Remove elements that have no text content and no meaningful children
        val emptyTags = setOf("div", "span", "p", "section", "article", "main")
        
        var changed = true
        var iterations = 0
        val maxIterations = 10

        while (changed && iterations < maxIterations) {
            changed = false
            iterations++

            for (tag in emptyTags) {
                val elements = doc.select(tag)
                for (element in elements) {
                    if (isEmptyElement(element)) {
                        element.remove()
                        changed = true
                    }
                }
            }
        }
    }

    private fun isEmptyElement(element: Element): Boolean {
        // Check if element has any non-whitespace text
        val text = element.text().trim()
        if (text.isNotEmpty()) return false

        // Check if element has any meaningful children (images, etc.)
        val meaningfulChildren = element.select("img, table, ul, ol, dl, pre, code, blockquote")
        if (meaningfulChildren.isNotEmpty()) return false

        return true
    }
}
