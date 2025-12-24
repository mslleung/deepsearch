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
     * Prepare a cleaned HTML preview for the preview quick answer agent.
     * 
     * Very aggressive cleaning strategy to extract ONLY prose content:
     * - Remove: ALL media (images, video, audio, svg, canvas, etc.)
     * - Remove: ALL tables (the agent explicitly skips sources with tables)
     * - Remove: ALL forms, navigation, chrome elements
     * - Remove: Code blocks, definition lists (structured data, not prose)
     * - Remove: Icon elements (common CSS class patterns)
     * - Remove: Navigation-like lists (lists of short links)
     * - Preserve: Only prose paragraphs (p, article, section, headings, blockquotes)
     * 
     * The goal is to support quick answers for queries that don't require
     * the full multi-modal markdown conversion pipeline.
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
        // Aggressive removal - keep ONLY prose content
        private val REMOVE_SELECTOR = listOf(
            // Scripts, styles, metadata
            "script", "style", "noscript", "link", "meta", "template",

            // Navigation and chrome
            "nav", "header", "footer", "aside", "menu", "menuitem",

            // Forms
            "form", "input", "button", "select", "textarea", "label",
            "fieldset", "legend", "output", "datalist",

            // ALL media - we only want prose text
            "img", "picture", "video", "audio", "svg", "canvas",
            "object", "embed", "iframe", "figure", "figcaption", "map", "area",

            // ALL tables - agent explicitly skips sources with tables
            "table", "tr", "td", "th", "thead", "tbody", "tfoot",
            "caption", "colgroup", "col",

            // Definition lists (usually structured key-value data, not prose)
            "dl", "dt", "dd",

            // Code blocks (technical snippets, not prose paragraphs)
            "pre", "code",

            // Icon elements - common patterns
            "i:empty", // Empty <i> tags are almost always icons
            "[class*='icon']", "[class*='Icon']",
            "[class*='fa-']", "[class*='fas ']", "[class*='far ']", "[class*='fab ']",
            "[class*='bi-']",
            "[class*='material-icons']", "[class*='material-symbols']",
            "[class*='glyphicon']",
            "[class*='feather']",

            // Hidden elements
            "[hidden]",
            "[style*='display: none']", "[style*='display:none']",
            "[aria-hidden='true']",

            // Common non-content patterns
            "[class*='breadcrumb']",
            "[class*='pagination']",
            "[class*='sidebar']",
            "[role='navigation']",
            "[role='banner']",
            "[role='contentinfo']"
        ).joinToString(", ")

        // Stripped down - only attributes useful for semantic understanding
        private val KEEP_ATTRIBUTES = setOf(
            "class", "id", "role", "aria-label"
        )

        // Tags to check for emptiness
        private val EMPTY_TAGS = setOf("div", "span", "p", "section", "article", "main", "li", "ul", "ol")

        // Only prose-containing elements are "meaningful"
        private const val MEANINGFUL_CHILDREN_SELECTOR = "p, h1, h2, h3, h4, h5, h6, blockquote, article, section"

        // Threshold for detecting navigation lists (lists where most items are just links)
        private const val NAV_LIST_LINK_THRESHOLD = 0.7f
        private const val NAV_LIST_MAX_TEXT_LENGTH = 50
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
        
        // Remove navigation-like lists (lists of short links)
        removeNavigationLists(doc)
        
        // Unwrap anchor tags - keep text but remove <a> wrapper (no value for prose extraction)
        doc.select("a").unwrap()
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
     * Remove navigation-like lists: lists where most items are just short links.
     * These are typically navigation menus, not prose content.
     */
    private fun removeNavigationLists(doc: Document) {
        val listsToRemove = mutableListOf<Element>()
        
        doc.select("ul, ol").forEach { list ->
            val items = list.select("> li")
            if (items.isEmpty()) return@forEach

            // Count items that are "link-only" (just an <a> with short text)
            val linkOnlyCount = items.count { li ->
                val text = li.text().trim()
                val children = li.children()
                val hasOnlyLink = children.size == 1 && children.first()?.tagName() == "a"
                hasOnlyLink && text.length < NAV_LIST_MAX_TEXT_LENGTH
            }

            // If most items are link-only, it's likely navigation
            if (items.size > 0 && linkOnlyCount.toFloat() / items.size > NAV_LIST_LINK_THRESHOLD) {
                listsToRemove.add(list)
            }
        }
        
        listsToRemove.forEach { it.remove() }
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

        // Check if element has any meaningful prose children
        return element.select(MEANINGFUL_CHILDREN_SELECTOR).isEmpty()
    }
}
