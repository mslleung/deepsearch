package io.deepsearch.application.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeVisitor
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
        // Single combined selector for all elements to remove (avoids multiple DOM traversals)
        private val REMOVE_SELECTOR = listOf(
            "script", "style", "noscript", "link", "meta", "svg", "iframe",
            "nav", "header", "footer", "aside", "form", "input", "button",
            "select", "textarea", "canvas", "video", "audio", "object", "embed",
            "[hidden]", "[style*='display: none']", "[style*='display:none']",
            "[aria-hidden='true']"
        ).joinToString(", ")

        // Attributes to keep (all others are stripped)
        private val KEEP_ATTRIBUTES = setOf(
            "class", "id", "role", "aria-label", "aria-labelledby",
            "href", "src", "alt", "title", "data-testid"
        )

        // Tags to check for emptiness
        private val EMPTY_TAGS = setOf("div", "span", "p", "section", "article", "main")

        // Selector for meaningful children (elements that make a container non-empty)
        private const val MEANINGFUL_CHILDREN_SELECTOR = "img, table, ul, ol, dl, pre, code, blockquote"
    }

    override fun prepareHtmlPreview(html: String, url: String): HtmlPreviewResult {
        val totalStart = System.currentTimeMillis()

        val parseStart = System.currentTimeMillis()
        val doc = Jsoup.parse(html)
        val parseTime = System.currentTimeMillis() - parseStart

        val title = doc.title().takeIf { it.isNotBlank() }
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }

        // Remove unwanted elements with single combined selector
        val removeStart = System.currentTimeMillis()
        doc.select(REMOVE_SELECTOR).remove()
        val removeTime = System.currentTimeMillis() - removeStart

        // Single traversal: strip attributes, remove comments, mark empty elements
        val processStart = System.currentTimeMillis()
        processElementsInSinglePass(doc)
        val processTime = System.currentTimeMillis() - processStart

        // Get the cleaned body HTML
        val serializeStart = System.currentTimeMillis()
        val cleanedHtml = doc.body().html()
        val serializeTime = System.currentTimeMillis() - serializeStart

        val totalTime = System.currentTimeMillis() - totalStart

        logger.debug(
            "HTML preview for {}: {} -> {} chars in {}ms (parse={}ms, remove={}ms, process={}ms, serialize={}ms)",
            url, html.length, cleanedHtml.length, totalTime, parseTime, removeTime, processTime, serializeTime
        )

        return HtmlPreviewResult(
            cleanedHtml = cleanedHtml,
            title = title,
            description = description
        )
    }

    /**
     * Single-pass processing using NodeVisitor for O(n) complexity:
     * - head(): Strip attributes, mark comments for removal
     * - tail(): Check for empty elements bottom-up (so nested empty elements are caught)
     */
    private fun processElementsInSinglePass(doc: Document) {
        val toRemove = mutableListOf<Node>()

        doc.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                // Mark comments for removal
                if (node.nodeName() == "#comment") {
                    toRemove.add(node)
                    return
                }

                // Strip attributes from elements
                if (node is Element) {
                    val attrsToRemove = node.attributes()
                        .filter { it.key.lowercase() !in KEEP_ATTRIBUTES }
                        .map { it.key }
                    attrsToRemove.forEach { node.removeAttr(it) }
                }
            }

            override fun tail(node: Node, depth: Int) {
                // Check for empty elements on the way back up (bottom-up traversal)
                // This ensures nested empty elements are caught in a single pass
                if (node is Element && node.tagName() in EMPTY_TAGS) {
                    if (isEmptyElement(node)) {
                        toRemove.add(node)
                    }
                }
            }
        })

        // Remove all marked nodes
        toRemove.forEach { it.remove() }
    }

    private fun isEmptyElement(element: Element): Boolean {
        // Check if element has any non-whitespace text
        if (element.text().trim().isNotEmpty()) return false

        // Check if element has any meaningful children (images, tables, etc.)
        return element.select(MEANINGFUL_CHILDREN_SELECTOR).isEmpty()
    }
}
