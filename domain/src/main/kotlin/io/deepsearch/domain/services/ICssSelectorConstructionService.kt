package io.deepsearch.domain.services

/**
 * Service for constructing CSS selectors from HTML snippets.
 * Used by agents that receive HTML snippets from LLMs and need to convert them
 * to CSS selectors for querying the full DOM.
 */
interface ICssSelectorConstructionService {
    /**
     * Constructs CSS selectors from an HTML snippet by parsing the root element's attributes
     * and finding all matching elements in the full HTML DOM using hierarchical parent context.
     * Only generates selectors for elements with matching structural hierarchy.
     * 
     * @param htmlSnippet The complete HTML of the element (from cleaned HTML)
     * @param cleanedHtml The cleaned HTML document that the LLM saw
     * @param fullHtml The full HTML document (original, uncleaned)
     * @return List of CSS selectors, one for each matching element. Empty list if snippet is invalid.
     */
    fun constructCssSelectorsFromSnippet(htmlSnippet: String, cleanedHtml: String, fullHtml: String): List<String>

    /**
     * Constructs a CSS selector from a stable identifier by finding the element in the HTML
     * and building a selector based on its attributes and structure.
     * 
     * @param identifier The data-ds-id value (e.g., "ds-semantic-5")
     * @param htmlWithIdentifiers The HTML document containing injected data-ds-id attributes
     * @return A CSS selector that can locate the element in the original webpage, or null if element not found
     */
    fun constructCssSelectorFromIdentifier(identifier: String, htmlWithIdentifiers: String): String?
}

