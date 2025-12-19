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
 * Service for manipulating DOM using Jsoup.
 * Provides operations equivalent to browser DOM manipulation,
 * allowing the browser to be released earlier in the extraction pipeline.
 */
interface IJsoupDomService {
    /**
     * Inject data-ds-id attributes into icon and image elements.
     * 
     * This mirrors the browser's behavior where icon/image extraction injects
     * data-ds-id attributes. When browser operations run in parallel with snapshot
     * capture, the snapshot HTML won't have these attributes, so we need to
     * inject them in Jsoup before doing media replacements.
     * 
     * @param doc The Jsoup document to modify (mutated in place)
     */
    fun injectMediaIdentifiers(doc: Document)
    
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
     * Inject data-ds-id attributes into elements found by CSS selectors.
     * This is used to restore the original behavior where identifiers were
     * injected into the browser DOM for stable subsequent operations.
     * 
     * @param doc The Jsoup document to modify (mutated in place)
     * @param injections List of pairs: (cssSelector, dataId)
     * @return Number of successful injections
     */
    fun injectIdentifiers(doc: Document, injections: List<Pair<String, String>>): Int
}

class JsoupDomService : IJsoupDomService {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        // Tags to exclude during text extraction (matches browser behavior)
        private val EXCLUDE_TAGS = setOf(
            "script", "style", "noscript", "button", "iframe",
            "nav", "header", "footer", "aside", "link", "meta"
        )
        
        // Icon selectors - must match extractIcons.ts exactly to get the same element order
        private val ICON_SELECTORS = listOf(
            // SVG icons
            "svg",
            // Font Awesome
            "i.fa", "i.fas", "i.far", "i.fab", "i.fal", "i.fad", "i.fass", "i.fasr", "i.fasl",
            "i[class^=fa-]", "i[class*=\" fa-\"]",
            // Bootstrap Icons
            "i.bi", "i[class^=bi-]", "i[class*=\" bi-\"]",
            // Material Design Icons
            "i.mdi", "i[class^=mdi-]", "i[class*=\" mdi-\"]",
            // Google Material Icons
            ".material-icons", ".material-icons-outlined", ".material-icons-round",
            ".material-icons-sharp", ".material-icons-two-tone",
            ".material-symbols-outlined", ".material-symbols-rounded", ".material-symbols-sharp",
            // Ionicons
            "ion-icon", "i[class^=ion-]", "i[class*=\" ion-\"]",
            // Glyphicons
            ".glyphicon",
            // Phosphor Icons
            "i.ph", "i[class^=ph-]", "i[class*=\" ph-\"]",
            // Remix Icons
            "i[class^=ri-]", "i[class*=\" ri-\"]",
            // Line Awesome
            "i.la", "i[class^=la-]", "i[class*=\" la-\"]",
            // Unicons
            "i.uil", "i.uis", "i.uim", "i.uib",
            // Boxicons
            "i.bx", "i[class^=bx-]", "i[class^=bxs-]", "i[class^=bxl-]",
            // Octicons
            ".octicon",
            // Feather Icons
            "[data-feather]",
            // Lucide Icons
            "[data-lucide]",
            // css.gg
            "i[class^=gg-]", "i[class*=\" gg-\"]",
            // Tabler Icons
            "i.ti", "i[class^=ti-]", "i[class*=\" ti-\"]",
            // Heroicons
            "[class*=heroicon]",
            // Generic icon patterns
            "i[class*=icon]", "i[class*=Icon]",
            "span[class*=icon]", "span[class*=Icon]",
            "[class*=icon-]", "[class*=Icon-]",
            "[class*=-icon]", "[class*=-Icon]",
            "[class*=ico-]", "[class*=glyph]",
            "[data-icon]",
            "i[aria-hidden=true]",
            "[role=img]:not(img)"
        )
        
        // Image selectors - must match extractImages.ts
        private val IMAGE_SELECTORS = listOf(
            "img",
            "picture img",
            "[style*=background-image]",
            "[data-src]",
            "[data-lazy-src]"
        )
    }
    
    override fun injectMediaIdentifiers(doc: Document) {
        // Inject icon identifiers (ds-icon-0, ds-icon-1, etc.)
        val iconSelector = ICON_SELECTORS.joinToString(", ")
        val iconElements = doc.select(iconSelector)
        var iconCounter = 0
        for (element in iconElements) {
            if (!element.hasAttr("data-ds-id")) {
                element.attr("data-ds-id", "ds-icon-${iconCounter++}")
            }
        }
        logger.debug("Injected data-ds-id into {} icon elements", iconCounter)
        
        // Inject image identifiers (ds-image-0, ds-image-1, etc.)
        val imageSelector = IMAGE_SELECTORS.joinToString(", ")
        val imageElements = doc.select(imageSelector)
        var imageCounter = 0
        for (element in imageElements) {
            if (!element.hasAttr("data-ds-id")) {
                element.attr("data-ds-id", "ds-image-${imageCounter++}")
            }
        }
        logger.debug("Injected data-ds-id into {} image elements", imageCounter)
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
    
    override fun injectIdentifiers(doc: Document, injections: List<Pair<String, String>>): Int {
        if (injections.isEmpty()) {
            return 0
        }
        
        var successCount = 0
        var failedCount = 0
        
        for ((cssSelector, dataId) in injections) {
            try {
                val element = doc.selectFirst(cssSelector)
                if (element != null) {
                    element.attr("data-ds-id", dataId)
                    successCount++
                } else {
                    // Try normalized selector for table elements
                    if (cssSelector.contains("table") || cssSelector.contains("tr") || cssSelector.contains("td") || cssSelector.contains("th")) {
                        val normalizedSelector = normalizeTableSelector(cssSelector)
                        if (normalizedSelector != cssSelector) {
                            val normalizedElement = doc.selectFirst(normalizedSelector)
                            if (normalizedElement != null) {
                                normalizedElement.attr("data-ds-id", dataId)
                                successCount++
                                logger.debug("Injected identifier '{}' using normalized selector '{}'", dataId, normalizedSelector)
                                continue
                            }
                        }
                    }
                    failedCount++
                    logger.debug("Failed to inject identifier '{}': element not found for selector '{}'", dataId, cssSelector)
                }
            } catch (e: Exception) {
                failedCount++
                logger.warn("Failed to inject identifier '{}' for selector '{}': {}", dataId, cssSelector, e.message)
            }
        }
        
        if (failedCount > 0) {
            logger.warn("Failed to inject {} of {} identifiers", failedCount, injections.size)
        }
        logger.debug("Successfully injected {} identifiers into Jsoup document", successCount)
        
        return successCount
    }
}

