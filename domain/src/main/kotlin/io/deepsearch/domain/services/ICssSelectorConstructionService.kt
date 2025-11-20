package io.deepsearch.domain.services

/**
 * Service for constructing CSS selectors from HTML snippets.
 * Used by agents that receive HTML snippets from LLMs and need to convert them
 * to CSS selectors for querying the full DOM.
 */
interface ICssSelectorConstructionService {
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

