package io.deepsearch.domain.services

import io.deepsearch.domain.models.valueobjects.StableElementId
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service for constructing CSS selectors from HTML snippets.
 * 
 * With the unified ID injection system, all elements have stable data-ds-id attributes.
 * This service primarily provides utility methods for working with these IDs.
 */
interface ICssSelectorConstructionService {
    /**
     * Constructs a CSS selector from a stable identifier.
     * Simply returns a data-ds-id attribute selector.
     *
     * @param identifier The data-ds-id value (e.g., "ds-element-5")
     * @param htmlWithIdentifiers The HTML document (unused, kept for API compatibility)
     * @return A CSS selector that can locate the element: [data-ds-id="$identifier"]
     */
    fun constructCssSelectorFromIdentifier(identifier: String, htmlWithIdentifiers: String): String?

    /**
     * Batch version that constructs CSS selectors for multiple identifiers.
     *
     * @param identifiers List of data-ds-id values (e.g., ["ds-element-0", "ds-element-1"])
     * @param htmlWithIdentifiers The HTML document (unused, kept for API compatibility)
     * @return A map from identifier to CSS selector
     */
    fun constructCssSelectorsFromIdentifiers(
        identifiers: List<String>,
        htmlWithIdentifiers: String
    ): Map<String, String?>
    
    /**
     * Constructs a CSS selector directly from a Jsoup Element.
     * Uses the element's data-ds-id if available, otherwise falls back to structural selector.
     *
     * @param element The Jsoup element to construct a selector for
     * @return A CSS selector that can locate the element
     */
    fun constructCssSelector(element: Element): String
    
    /**
     * Constructs a CSS selector from a StableElementId.
     *
     * @param id The stable element ID
     * @return A CSS selector: [data-ds-id="ds-{type}-{id}"]
     */
    fun constructCssSelector(id: StableElementId): String
}

/**
 * Service for constructing CSS selectors.
 * 
 * With the unified ID injection system, selectors are simply data-ds-id attribute selectors.
 */
class CssSelectorConstructionService : ICssSelectorConstructionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun constructCssSelectorFromIdentifier(identifier: String, htmlWithIdentifiers: String): String? {
        // Validate the identifier format
        if (!identifier.startsWith("ds-")) {
            logger.warn("Invalid identifier format (expected ds-* prefix): {}", identifier)
            return null
        }
        
        return "[data-ds-id=\"$identifier\"]"
    }

    override fun constructCssSelectorsFromIdentifiers(
        identifiers: List<String>,
        htmlWithIdentifiers: String
    ): Map<String, String?> {
        return identifiers.associateWith { identifier ->
            constructCssSelectorFromIdentifier(identifier, htmlWithIdentifiers)
        }
    }
    
    override fun constructCssSelector(element: Element): String {
        // Prefer data-ds-id if available
        val dsId = element.attr("data-ds-id")
        if (dsId.isNotBlank()) {
            return "[data-ds-id=\"$dsId\"]"
        }
        
        // Fallback: build a hierarchical selector for elements without data-ds-id
        // This should be rare since injectStableIds should cover all needed elements
        logger.debug("Element has no data-ds-id, building fallback selector for <{}>", element.tagName())
        return buildFallbackSelector(element)
    }
    
    override fun constructCssSelector(id: StableElementId): String {
        return id.cssSelector
    }
    
    /**
     * Builds a fallback selector for elements without data-ds-id.
     * Uses tag name with nth-of-type for uniqueness.
     */
    private fun buildFallbackSelector(element: Element): String {
        val parts = mutableListOf<String>()
        var current: Element? = element
        var level = 0
        val maxLevels = 5
        
        while (current != null && level < maxLevels) {
            val tagName = current.tagName()
            
            // Skip body and html
            if (tagName == "body" || tagName == "html") {
                break
            }
            
            val parent = current.parent()
            val selectorPart = if (parent != null) {
                val siblingsOfSameType = parent.children().filter { it.tagName() == tagName }
                if (siblingsOfSameType.size > 1) {
                    val position = siblingsOfSameType.indexOf(current) + 1
                    "$tagName:nth-of-type($position)"
                } else {
                    tagName
                }
            } else {
                tagName
            }
            
            parts.add(0, selectorPart)
            current = parent
            level++
        }
        
        return parts.joinToString(" > ")
    }
}
