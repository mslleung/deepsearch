package io.deepsearch.domain.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service for constructing CSS selectors from HTML snippets.
 * Parses HTML snippets provided by LLMs and generates robust CSS selectors
 * that can be used to query elements in the full DOM.
 */
class CssSelectorConstructionService : ICssSelectorConstructionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
    override fun constructCssSelectorsFromSnippet(
        htmlSnippet: String,
        cleanedHtml: String,
        fullHtml: String
    ): List<String> {
        return try {
            // Parse the snippet to extract the root element's attributes
            val snippetDoc = Jsoup.parseBodyFragment(htmlSnippet)
            val rootElement = snippetDoc.body().child(0)
            
            val tagName = rootElement.tagName()
            val id = rootElement.attr("id")
            val classes = rootElement.classNames()
            
            // Build base CSS selector for the target element
            val baseSelector = buildBaseSelector(tagName, id, classes)
            
            logger.debug("Constructed base selector: {} from snippet", baseSelector)
            
            // First, find matching elements in the CLEANED HTML (which the LLM saw)
            val cleanedDoc = Jsoup.parse(cleanedHtml)
            val cleanedMatchingElements = cleanedDoc.select(baseSelector)
            
            if (cleanedMatchingElements.isEmpty()) {
                logger.warn("No elements found in cleaned HTML for selector: {}", baseSelector)
                return emptyList()
            }
            
            // Filter by structural match in cleaned HTML
            val structurallyMatchingElements = cleanedMatchingElements.filter { candidate ->
                hasMatchingStructure(rootElement, candidate)
            }
            
            logger.debug("Filtered {} candidates to {} structurally-matching elements in cleaned HTML", 
                cleanedMatchingElements.size, structurallyMatchingElements.size)
            
            if (structurallyMatchingElements.isEmpty()) {
                logger.warn("No structurally-matching elements found for snippet: {}", htmlSnippet.take(100))
                return emptyList()
            }
            
            // Now find the same elements in the ORIGINAL HTML using their selectors
            val fullDoc = Jsoup.parse(fullHtml)
            
            // If single match in cleaned HTML, use the base selector
            if (structurallyMatchingElements.size == 1) {
                logger.debug("Single structurally-matching element found for selector: {}", baseSelector)
                return listOf(baseSelector)
            }
            
            // Multiple matches: construct hierarchical selectors from cleaned HTML elements
            // but validate they work on the original HTML
            return structurallyMatchingElements.mapNotNull { cleanedElement ->
                val hierarchicalSelector = buildHierarchicalSelector(cleanedElement, cleanedDoc)
                
                // Verify the selector works on original HTML
                val originalMatches = fullDoc.select(hierarchicalSelector)
                if (originalMatches.isEmpty()) {
                    logger.warn("Selector {} from cleaned HTML doesn't match original HTML", hierarchicalSelector)
                    null
                } else {
                    hierarchicalSelector
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to construct CSS selector from HTML snippet: {}", htmlSnippet.take(100), e)
            emptyList()
        }
    }
    
    /**
     * Escapes special characters in CSS identifiers (IDs and class names) to make them
     * safe for use in CSS selectors. According to CSS spec, certain characters need to be
     * escaped with a backslash when used in selectors.
     * 
     * @param identifier The raw ID or class name from HTML
     * @return The escaped identifier safe for use in CSS selectors
     */
    private fun escapeCssIdentifier(identifier: String): String {
        // CSS special characters that need escaping:
        // : . [ ] ( ) ~ ! @ $ % ^ & * + = , / ' " ; ? # space
        val specialChars = setOf(
            ':', '.', '[', ']', '(', ')', '~', '!', '@', '$', '%', '^', '&', '*', 
            '+', '=', ',', '/', '\'', '"', ';', '?', '#', ' '
        )
        
        return identifier.map { char ->
            if (char in specialChars) {
                "\\$char"
            } else {
                char.toString()
            }
        }.joinToString("")
    }
    
    /**
     * Builds a base CSS selector for an element using its tag, id, and classes.
     */
    private fun buildBaseSelector(tagName: String, id: String, classes: Set<String>): String {
        return when {
            id.isNotBlank() -> "#${escapeCssIdentifier(id)}"
            classes.isNotEmpty() -> "$tagName.${classes.map { escapeCssIdentifier(it) }.joinToString(".")}"
            else -> tagName
        }
    }
    
    /**
     * Compares structural similarity between snippet element and candidate element.
     * Both elements are from cleaned HTML, so we can do a direct structural comparison.
     * 
     * @param snippetElement The root element from the LLM-provided HTML snippet (from cleaned HTML)
     * @param candidateElement The candidate element from cleaned HTML
     * @return true if structures match exactly
     */
    private fun hasMatchingStructure(snippetElement: Element, candidateElement: Element): Boolean {
        // if a candidate in the original html has the base selector and the first 1000 characters look exactly the same, consider it a match
        val matches = snippetElement.outerHtml().take(1000) == candidateElement.outerHtml().take(1000)

        return matches
    }
    
    /**
     * Builds a hierarchical CSS selector for an element by walking up the DOM tree
     * to find unique ancestors and constructing a specific path.
     * 
     * Strategy:
     * 1. Walk up to 5 levels to find a unique ancestor (with id or distinctive classes)
     * 2. Build a selector path from that ancestor to the target element
     * 3. Include positional selectors (:nth-of-type or :nth-child) when needed for uniqueness
     * 
     * @param element The target element to build a selector for
     * @param doc The full document (for validating selector uniqueness)
     * @return A hierarchical CSS selector, or null if unable to construct a unique one
     */
    private fun buildHierarchicalSelector(element: Element, doc: Document): String {
        val selectorParts = mutableListOf<String>()
        var currentElement: Element? = element
        var foundUniqueAncestor = false
        val maxLevels = 5
        var level = 0
        
        // Walk up the tree to build the selector path
        while (currentElement != null && level < maxLevels) {
            val tagName = currentElement.tagName()
            val id = currentElement.attr("id")
            val classes = currentElement.classNames()
            
            // Build selector part for this element
            val selectorPart = when {
                // If element has an ID, use it as an anchor point
                id.isNotBlank() -> {
                    foundUniqueAncestor = true
                    "#${escapeCssIdentifier(id)}"
                }
                // If element has classes, include them
                classes.isNotEmpty() -> {
                    val classSelector = "$tagName.${classes.map { escapeCssIdentifier(it) }.joinToString(".")}"
                    // Check if this selector is unique at this level
                    val parent = currentElement.parent()
                    if (parent != null) {
                        val siblingsMatching = parent.children().filter { sibling ->
                            sibling.tagName() == tagName && sibling.classNames() == classes
                        }
                        if (siblingsMatching.size > 1) {
                            // Need positional selector
                            val position = siblingsMatching.indexOf(currentElement) + 1
                            "$classSelector:nth-of-type($position)"
                        } else {
                            classSelector
                        }
                    } else {
                        classSelector
                    }
                }
                // No id or classes, use tag with position
                else -> {
                    val parent = currentElement.parent()
                    if (parent != null) {
                        val siblingsOfSameType = parent.children().filter { it.tagName() == tagName }
                        if (siblingsOfSameType.size > 1) {
                            val position = siblingsOfSameType.indexOf(currentElement) + 1
                            "$tagName:nth-of-type($position)"
                        } else {
                            tagName
                        }
                    } else {
                        tagName
                    }
                }
            }
            
            selectorParts.add(0, selectorPart)
            
            // If we found a unique anchor (ID), stop here
            if (foundUniqueAncestor) {
                break
            }
            
            // Move up to parent
            currentElement = currentElement.parent()
            level++
            
            // Stop at body or html
            if (currentElement?.tagName() == "body" || currentElement?.tagName() == "html") {
                break
            }
        }
        
        // Construct the final selector with " > " combinator for direct child relationships
        val hierarchicalSelector = selectorParts.joinToString(" > ")
        
        // Validate that this selector is unique in the document
        val matches = doc.select(hierarchicalSelector)
        if (matches.size != 1) {
            logger.warn("Hierarchical selector '{}' matches {} elements, expected 1", hierarchicalSelector, matches.size)
            // Return it anyway, as it's still more specific than the base selector
        }
        
        logger.debug("Built hierarchical selector: {}", hierarchicalSelector)
        return hierarchicalSelector
    }
}

