package io.deepsearch.application.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.NodeVisitor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Result of link-relevance HTML preparation.
 */
data class LinkRelevanceHtmlResult(
    val cleanedHtml: String
)

interface ILinkRelevanceHtmlService {
    /**
     * Prepare cleaned HTML optimized for link relevance analysis.
     * 
     * Preserves anchor tags and their surrounding context while stripping:
     * - Scripts, styles, noscript, template
     * - Forms, inputs, buttons
     * - Media elements (img, svg, video, audio, canvas, iframe)
     * - Comments
     * - All attributes except href and aria-label on anchors
     * 
     * Result is much smaller (~10-30KB vs ~500KB-1MB) but retains
     * all information needed for link discovery and relevance analysis.
     * 
     * @param html The raw HTML content
     * @param url The URL of the page (for logging)
     * @return Cleaned HTML optimized for link analysis
     */
    fun prepareLinkRelevanceHtml(html: String, url: String): LinkRelevanceHtmlResult
}

class LinkRelevanceHtmlService : ILinkRelevanceHtmlService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // Pre-configured HTML parser with optimized settings
        private val HTML_PARSER: Parser by lazy {
            Parser.htmlParser().setTrackErrors(0)
        }
        
        // Regex patterns for pre-stripping heavy elements before parsing
        private val SCRIPT_PATTERN = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
        private val STYLE_PATTERN = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
        private val SVG_PATTERN = Regex("<svg[^>]*>[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE)
        private val NOSCRIPT_PATTERN = Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE)
    }

    override fun prepareLinkRelevanceHtml(html: String, url: String): LinkRelevanceHtmlResult {
        val totalStart = System.currentTimeMillis()
        
        // Pre-strip heavy elements with regex before parsing (much faster for large HTML)
        val preStripped = html
            .replace(SCRIPT_PATTERN, "")
            .replace(STYLE_PATTERN, "")
            .replace(SVG_PATTERN, "")
            .replace(NOSCRIPT_PATTERN, "")
        
        val doc = Jsoup.parse(preStripped, "", HTML_PARSER)
        
        // Remove non-content elements (keep anchors and surrounding context)
        doc.select(
            "script, style, noscript, template, link, meta, " +
            "form, input, select, textarea, button, fieldset, legend, " +
            "img, picture, video, audio, svg, canvas, object, embed, iframe, figure, figcaption, " +
            "i:empty, [class*='icon'], [class*='Icon'], [class*='fa-']"
        ).remove()
        
        // Remove comments
        val comments = mutableListOf<Node>()
        doc.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node.nodeName() == "#comment") comments.add(node)
            }
            override fun tail(node: Node, depth: Int) {}
        })
        comments.forEach { it.remove() }
        
        // Strip attributes - keep only href and aria-label on anchors
        doc.select("*").forEach { element ->
            val attrsToKeep = if (element.tagName() == "a") {
                element.attributes().filter { it.key in setOf("href", "aria-label") }
            } else {
                emptyList()
            }
            element.clearAttributes()
            attrsToKeep.forEach { element.attr(it.key, it.value) }
        }
        
        // Remove duplicate anchors (keep first occurrence of each href)
        val seenHrefs = mutableSetOf<String>()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) {
                if (href in seenHrefs) anchor.remove()
                else seenHrefs.add(href)
            }
        }
        
        // Truncate long text nodes
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.length > 100) textNode.text(text.take(100) + "...")
            }
        }
        
        // Remove empty elements (but keep anchors)
        var changed = true
        while (changed) {
            changed = false
            val empty = doc.select("*").filter { el ->
                el.tagName() != "a" && el.children().isEmpty() && 
                el.ownText().isBlank() && el.tagName() !in setOf("br", "hr")
            }
            if (empty.isNotEmpty()) {
                changed = true
                empty.forEach { it.remove() }
            }
        }
        
        val cleanedHtml = doc.body().html()
        val totalTime = System.currentTimeMillis() - totalStart
        
        logger.debug(
            "Link relevance HTML for {}: {} -> {} chars in {}ms",
            url, html.length, cleanedHtml.length, totalTime
        )
        
        return LinkRelevanceHtmlResult(cleanedHtml = cleanedHtml)
    }
}

