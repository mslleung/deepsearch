package io.deepsearch.domain.services

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Replacement instruction for CSS selector-targeted elements.
 */
data class CssSelectorReplacement(
    val cssSelector: String,
    val text: String?
)

/**
 * Mapping between a placeholder token and its replacement text.
 * Used to defer media insertion until after HTML-to-Markdown conversion.
 */
data class MediaPlaceholderMapping(
    /** The placeholder token inserted into the DOM, e.g., "{{MEDIA_0}}" */
    val placeholder: String,
    /** The actual text to replace the placeholder with, e.g., "Search icon" or "![desc](#img-1)" */
    val text: String
)

/**
 * Prefix types for placeholder IDs to avoid collisions when calling
 * replaceElementsWithPlaceholders multiple times.
 */
enum class PlaceholderPrefix(val prefix: String) {
    /** For icons and images */
    MEDIA("MEDIA"),
    /** For table markdown */
    TABLE("TABLE"),
    /** For list markdown */
    LIST("LIST"),
    /** For hidden container content (accordions, collapsed sections) */
    HIDDEN("HIDDEN")
}

/**
 * Service for manipulating DOM using Jsoup.
 * Provides operations equivalent to browser DOM manipulation,
 * allowing the browser to be released earlier in the extraction pipeline.
 * 
 * Note: ID injection is no longer needed in Jsoup since the browser's
 * injectStableIds() injects all data-ds-id attributes before snapshot capture.
 */
interface IJsoupDomService {
    /**
     * Replace elements matching CSS selectors with text content.
     * If text is null, the element is removed.
     * 
     * @param doc The Jsoup document to modify (mutated in place)
     * @param replacements List of CSS selector to text replacements
     */
    fun replaceElementsWithText(doc: Document, replacements: List<CssSelectorReplacement>)
    
    /**
     * Remove elements matching CSS selectors from the document.
     * 
     * @param doc The Jsoup document to modify (mutated in place)
     * @param selectors List of CSS selectors for elements to remove
     */
    fun removeElements(doc: Document, selectors: List<String>)
    
    /**
     * Extract text content from the document body.
     * Mimics browser text extraction behavior:
     * - Walks DOM tree in document order
     * - Excludes script, style, noscript, button, iframe, nav, header, footer, aside, link, meta tags
     * - Collects text nodes, joins with newlines
     * 
     * @param doc The Jsoup document to extract text from
     * @return Extracted text content
     */
    fun extractTextContent(doc: Document): String
    
    /**
     * Extract text content from the document body with image placeholders.
     * Similar to extractTextContent, but emits placeholders for images
     * to indicate their presence to downstream agents.
     * 
     * Format: `<image placeholder alt="..."/>` for images with alt text,
     * or `<image placeholder/>` for images without alt text.
     * 
     * @param doc The Jsoup document to extract text from
     * @return Extracted text content with image placeholders
     */
    fun extractTextContentWithImagePlaceholders(doc: Document): String
    
    /**
     * Extract text content from specific elements by CSS selectors.
     * 
     * @param doc The Jsoup document
     * @param selectors List of CSS selectors
     * @return Map of CSS selector to extracted text (empty string if not found)
     */
    fun extractElementsText(doc: Document, selectors: List<String>): Map<String, String>
    
    /**
     * Get the outer HTML of an element by CSS selector.
     * 
     * @param doc The Jsoup document
     * @param selector CSS selector
     * @return Outer HTML of the element, or null if not found
     */
    fun getElementHtml(doc: Document, selector: String): String?
    
    /**
     * Get the outer HTML of multiple elements by CSS selectors in a batch.
     * 
     * @param doc The Jsoup document
     * @param selectors List of CSS selectors
     * @return Map of CSS selector to outer HTML (null if not found)
     */
    fun getElementsHtml(doc: Document, selectors: List<String>): Map<String, String?>
    
    /**
     * Replace elements with placeholder tokens instead of actual text.
     * Used to defer media insertion until after HTML-to-Markdown conversion,
     * preventing markdown syntax from being escaped.
     * 
     * @param doc The Jsoup document to modify (mutated in place)
     * @param replacements List of CSS selector to text replacements
     * @param placeholderPrefix Prefix for placeholder IDs. Use different prefixes
     *        when calling this function multiple times to avoid ID collisions.
     * @return Map of placeholder token to MediaPlaceholderMapping for later substitution
     */
    fun replaceElementsWithPlaceholders(
        doc: Document,
        replacements: List<CssSelectorReplacement>,
        placeholderPrefix: PlaceholderPrefix = PlaceholderPrefix.MEDIA
    ): Map<String, MediaPlaceholderMapping>
    
    /**
     * Cleans up the DOM to prevent markdown conversion artifacts.
     * Removes empty elements and structures that would produce unwanted markdown markers.
     * 
     * This should be called BEFORE HTML-to-Markdown conversion to prevent:
     * - Empty list items producing `* ` artifacts
     * - Empty blockquote children producing orphan `>` markers
     * - Empty paragraphs/divs producing excessive blank lines
     * - Carousel/slider navigation dots producing stray markers
     * 
     * @param doc The Jsoup document to modify (mutated in place)
     * @return Cleanup statistics for logging
     */
    fun cleanupForMarkdownConversion(doc: Document): MarkdownCleanupStats
    
}

/**
 * Statistics from markdown cleanup operation.
 */
data class MarkdownCleanupStats(
    val emptyListItemsRemoved: Int,
    val emptyBlockquoteChildrenRemoved: Int,
    val emptyElementsRemoved: Int,
)

class JsoupDomService : IJsoupDomService {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        // Tags to exclude during text extraction (matches browser behavior)
        private val EXCLUDE_TAGS = setOf(
            "script", "style", "noscript", "button", "iframe",
            "nav", "header", "footer", "aside", "link", "meta"
        )
    }
    
    override fun replaceElementsWithText(doc: Document, replacements: List<CssSelectorReplacement>) {
        if (replacements.isEmpty()) {
            return
        }
        
        var replaced = 0
        var removed = 0
        var notFound = 0
        
        for (replacement in replacements) {
            try {
                var elements = doc.select(replacement.cssSelector)
                
                // If selector didn't match, try normalizing for table tbody insertion
                // Browser selectors like "table > tr" won't match Jsoup's "table > tbody > tr"
                if (elements.isEmpty() && replacement.cssSelector.contains("table")) {
                    val normalizedSelector = normalizeTableSelector(replacement.cssSelector)
                    if (normalizedSelector != replacement.cssSelector) {
                        elements = doc.select(normalizedSelector)
                        if (elements.isNotEmpty()) {
                            logger.debug("Selector '{}' matched after normalizing to '{}'", 
                                replacement.cssSelector, normalizedSelector)
                        }
                    }
                }
                
                if (elements.isEmpty()) {
                    notFound++
                    logger.debug("No elements found for selector: {}", replacement.cssSelector)
                }
                
                for (element in elements) {
                    if (replacement.text != null) {
                        // Replace element with a span containing the text
                        // Using span to preserve inline flow
                        val textContainer = Element(Tag.valueOf("span"), "")
                        textContainer.appendText(replacement.text)
                        element.replaceWith(textContainer)
                        replaced++
                    } else {
                        // Remove element
                        element.remove()
                        removed++
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to replace element for selector '{}': {}", replacement.cssSelector, e.message)
            }
        }
        
        if (notFound > 0) {
            logger.warn("{} of {} media replacements had no matching elements (CSS selector mismatch)", 
                notFound, replacements.size)
        }
        logger.debug("Replaced {} elements, removed {} elements", replaced, removed)
    }
    
    /**
     * Normalizes CSS selectors to handle Jsoup's HTML normalization.
     * Jsoup adds implicit elements like <tbody> to tables, which breaks
     * browser-generated selectors like "table > tr" (should be "table > tbody > tr").
     */
    private fun normalizeTableSelector(selector: String): String {
        // Handle direct child selectors that skip tbody
        // "table > tr" -> "table > tbody > tr" or "table > thead > tr" etc.
        return selector
            .replace(Regex("table\\s*>\\s*tr"), "table > tbody > tr")
            .replace(Regex("table\\s*>\\s*td"), "table > tbody > tr > td")
            .replace(Regex("table\\s*>\\s*th"), "table > thead > tr > th")
    }
    
    override fun removeElements(doc: Document, selectors: List<String>) {
        if (selectors.isEmpty()) {
            return
        }
        
        var removed = 0
        
        for (selector in selectors) {
            try {
                val elements = doc.select(selector)
                for (element in elements) {
                    element.remove()
                    removed++
                }
            } catch (e: Exception) {
                logger.warn("Failed to remove element for selector '{}': {}", selector, e.message)
            }
        }
        
        logger.debug("Removed {} elements", removed)
    }
    
    override fun extractTextContent(doc: Document): String {
        val body = doc.body() ?: return ""
        return extractTextFromElement(body, includeImagePlaceholders = false)
    }
    
    override fun extractTextContentWithImagePlaceholders(doc: Document): String {
        val body = doc.body() ?: return ""
        return extractTextFromElement(body, includeImagePlaceholders = true)
    }
    
    override fun extractElementsText(doc: Document, selectors: List<String>): Map<String, String> {
        if (selectors.isEmpty()) {
            return emptyMap()
        }
        
        return selectors.associateWith { selector ->
            try {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    // Match browser behavior: use last matching element
                    val target = elements.last()
                    extractTextFromElement(target!!, includeImagePlaceholders = false)
                } else {
                    ""
                }
            } catch (e: Exception) {
                logger.warn("Failed to extract text for selector '{}': {}", selector, e.message)
                ""
            }
        }
    }
    
    override fun getElementHtml(doc: Document, selector: String): String? {
        return try {
            doc.selectFirst(selector)?.outerHtml()
        } catch (e: Exception) {
            logger.warn("Failed to get HTML for selector '{}': {}", selector, e.message)
            null
        }
    }
    
    override fun getElementsHtml(doc: Document, selectors: List<String>): Map<String, String?> {
        if (selectors.isEmpty()) {
            return emptyMap()
        }
        
        return selectors.associateWith { selector ->
            try {
                doc.selectFirst(selector)?.outerHtml()
            } catch (e: Exception) {
                logger.warn("Failed to get HTML for selector '{}': {}", selector, e.message)
                null
            }
        }
    }
    
    override fun replaceElementsWithPlaceholders(
        doc: Document,
        replacements: List<CssSelectorReplacement>,
        placeholderPrefix: PlaceholderPrefix
    ): Map<String, MediaPlaceholderMapping> {
        if (replacements.isEmpty()) {
            return emptyMap()
        }
        
        val placeholderMap = mutableMapOf<String, MediaPlaceholderMapping>()
        var placeholderCounter = 0
        var replaced = 0
        var removed = 0
        var notFound = 0
        
        for (replacement in replacements) {
            try {
                var elements = doc.select(replacement.cssSelector)
                
                // If selector didn't match, try normalizing for table tbody insertion
                if (elements.isEmpty() && replacement.cssSelector.contains("table")) {
                    val normalizedSelector = normalizeTableSelector(replacement.cssSelector)
                    if (normalizedSelector != replacement.cssSelector) {
                        elements = doc.select(normalizedSelector)
                        if (elements.isNotEmpty()) {
                            logger.debug("Selector '{}' matched after normalizing to '{}'", 
                                replacement.cssSelector, normalizedSelector)
                        }
                    }
                }
                
                if (elements.isEmpty()) {
                    notFound++
                    logger.debug("No elements found for selector: {}", replacement.cssSelector)
                }
                
                for (element in elements) {
                    if (replacement.text != null) {
                        // Generate unique placeholder token using the provided prefix
                        val placeholderId = "${placeholderPrefix.prefix}_${placeholderCounter++}"
                        val placeholder = "{{$placeholderId}}"
                        
                        // Store the mapping
                        placeholderMap[placeholderId] = MediaPlaceholderMapping(
                            placeholder = placeholder,
                            text = replacement.text
                        )
                        
                        // Replace element with a span containing the placeholder
                        val textContainer = Element(Tag.valueOf("span"), "")
                        textContainer.appendText(placeholder)
                        element.replaceWith(textContainer)
                        replaced++
                    } else {
                        // Remove element (no placeholder needed)
                        element.remove()
                        removed++
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to replace element for selector '{}': {}", replacement.cssSelector, e.message)
            }
        }
        
        if (notFound > 0) {
            logger.warn("{} of {} {} replacements had no matching elements (CSS selector mismatch)", 
                notFound, replacements.size, placeholderPrefix.name)
        }
        logger.debug("Replaced {} elements with placeholders, removed {} elements ({} prefix)", replaced, removed, placeholderPrefix.name)
        
        return placeholderMap
    }
    
    override fun cleanupForMarkdownConversion(doc: Document): MarkdownCleanupStats {
        var emptyListItemsRemoved = 0
        var emptyBlockquoteChildrenRemoved = 0
        var emptyElementsRemoved = 0
        
        // ===== Step 1: Remove empty list items =====
        // Empty <li> elements produce `* ` or `- ` artifacts in markdown
        // Check recursively since removing children might leave parents empty
        var removedInPass: Int
        do {
            removedInPass = 0
            val emptyListItems = doc.select("li").filter { li ->
                isEffectivelyEmpty(li)
            }
            for (li in emptyListItems) {
                li.remove()
                emptyListItemsRemoved++
                removedInPass++
            }
        } while (removedInPass > 0)
        
        // ===== Step 2: Remove empty lists (ul/ol with no items left) =====
        doc.select("ul, ol").filter { list ->
            list.children().isEmpty() || list.children().all { it.tagName() == "li" && isEffectivelyEmpty(it) }
        }.forEach { list ->
            list.remove()
            emptyElementsRemoved++
        }
        
        // ===== Step 3: Clean up blockquotes =====
        // Remove empty children that would produce orphan `>` markers
        for (blockquote in doc.select("blockquote")) {
            // Remove empty child elements (empty <p>, <div>, <cite>, <footer>, etc.)
            val emptyChildren = blockquote.children().filter { child ->
                isEffectivelyEmpty(child)
            }
            for (child in emptyChildren) {
                child.remove()
                emptyBlockquoteChildrenRemoved++
            }
            
            // Also clean up text nodes that are only whitespace
            blockquote.childNodes()
                .filterIsInstance<TextNode>()
                .filter { it.text().isBlank() }
                .forEach { it.remove() }
        }
        
        // Remove blockquotes that became empty
        doc.select("blockquote").filter { isEffectivelyEmpty(it) }.forEach { 
            it.remove()
            emptyElementsRemoved++
        }
        
        // ===== Step 4: Remove other empty block elements that cause blank lines =====
        // These elements can cause excessive blank lines or empty markers
        val emptyBlockSelectors = listOf("p", "div", "span", "section", "article")
        for (selector in emptyBlockSelectors) {
            // Only remove truly empty elements (no meaningful children)
            // Be careful not to remove structural containers
            doc.select(selector).filter { element ->
                isEffectivelyEmpty(element) && !hasStructuralRole(element)
            }.forEach { element ->
                element.remove()
                emptyElementsRemoved++
            }
        }
        
        // ===== Step 5: Clean up excessive <br> elements =====
        // Multiple consecutive <br> elements can cause blank line issues
        // Replace 3+ consecutive <br> with just 2
        for (br in doc.select("br").toList()) {
            var consecutiveBrCount = 1
            var nextSibling = br.nextSibling()
            val toRemove = mutableListOf<Node>()
            
            while (nextSibling != null) {
                when {
                    nextSibling is Element && nextSibling.tagName() == "br" -> {
                        consecutiveBrCount++
                        if (consecutiveBrCount > 2) {
                            toRemove.add(nextSibling)
                        }
                        nextSibling = nextSibling.nextSibling()
                    }
                    nextSibling is TextNode && nextSibling.text().isBlank() -> {
                        // Skip whitespace-only text nodes
                        nextSibling = nextSibling.nextSibling()
                    }
                    else -> break
                }
            }
            
            toRemove.forEach { it.remove() }
        }
        
        // ===== Step 6: Remove elements with only non-breaking spaces or zero-width chars =====
        // These can cause invisible empty markers
        val invisibleCharsPattern = Regex("^[\\s\\u00A0\\u200B\\u200C\\u200D\\uFEFF]*$")
        doc.select("*").filter { element ->
            element.ownText().matches(invisibleCharsPattern) && 
            element.children().isEmpty() &&
            element.tagName() !in setOf("br", "hr", "img", "input", "meta", "link")
        }.forEach { element ->
            // Check if removal is safe (not structural)
            if (!hasStructuralRole(element) && element.parent() != null) {
                element.remove()
                emptyElementsRemoved++
            }
        }
        
        val stats = MarkdownCleanupStats(
            emptyListItemsRemoved = emptyListItemsRemoved,
            emptyBlockquoteChildrenRemoved = emptyBlockquoteChildrenRemoved,
            emptyElementsRemoved = emptyElementsRemoved,
        )
        
        logger.debug(
            "Markdown cleanup: {} empty list items, {} empty blockquote children, {} empty elements",
            stats.emptyListItemsRemoved,
            stats.emptyBlockquoteChildrenRemoved,
            stats.emptyElementsRemoved,
        )
        
        return stats
    }
    
    /**
     * Checks if an element is effectively empty (no meaningful text content).
     * An element is considered empty if:
     * - It has no text content (or only whitespace)
     * - It has no meaningful child elements (images, inputs, etc.)
     */
    private fun isEffectivelyEmpty(element: Element): Boolean {
        // Check for meaningful text
        val text = element.text().trim()
        if (text.isNotEmpty()) {
            return false
        }
        
        // Check for meaningful child elements that shouldn't be removed
        val meaningfulChildren = element.select("img, svg, video, audio, canvas, iframe, input, textarea, select, button, object, embed")
        if (meaningfulChildren.isNotEmpty()) {
            return false
        }
        
        // Check for placeholder text (our own markers)
        if (element.html().contains("{{")) {
            return false
        }
        
        return true
    }
    
    /**
     * Checks if an element has a structural role and shouldn't be removed even if empty.
     * These elements might be used for layout or have semantic meaning.
     */
    private fun hasStructuralRole(element: Element): Boolean {
        // Elements with IDs are often used as anchors/references
        if (element.id().isNotEmpty()) {
            return true
        }
        
        // Elements with ARIA roles have semantic meaning
        if (element.hasAttr("role")) {
            return true
        }
        
        // Elements that are structural containers (could be populated dynamically)
        val structuralClasses = setOf(
            "container", "wrapper", "main", "content", "body",
            "row", "col", "column", "grid", "flex"
        )
        val elementClasses = element.classNames().map { it.lowercase() }
        if (elementClasses.any { cls -> structuralClasses.any { cls.contains(it) } }) {
            return true
        }
        
        return false
    }
    
    /**
     * Extract text from an element using depth-first traversal.
     * Mimics the browser's text extraction algorithm.
     * 
     * Note: Uses getWholeText() instead of text() to preserve internal newlines,
     * which is critical for markdown tables and other multi-line content.
     * 
     * @param root The root element to extract text from
     * @param includeImagePlaceholders If true, emit placeholders for img elements
     */
    private fun extractTextFromElement(root: Element, includeImagePlaceholders: Boolean = false): String {
        val result = mutableListOf<String>()
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            
            when (node) {
                is TextNode -> {
                    // Use getWholeText() to preserve internal newlines (e.g., in markdown tables)
                    // text() normalizes whitespace and converts newlines to spaces
                    val text = node.getWholeText().trim()
                    if (text.isNotEmpty()) {
                        result.add(text)
                    }
                }
                is Element -> {
                    val tagName = node.tagName().lowercase()
                    
                    // Handle image placeholders
                    if (includeImagePlaceholders && tagName == "img") {
                        val alt = node.attr("alt").trim()
                        val placeholder = if (alt.isNotEmpty()) {
                            "<image placeholder alt=\"$alt\"/>"
                        } else {
                            "<image placeholder/>"
                        }
                        result.add(placeholder)
                        // Don't process children of img (there shouldn't be any)
                    } else if (tagName !in EXCLUDE_TAGS) {
                        // Add children in reverse order so they're processed in document order
                        val children = node.childNodes()
                        for (i in children.indices.reversed()) {
                            stack.addLast(children[i])
                        }
                    }
                }
            }
        }
        
        return result.joinToString("\n")
    }
}

