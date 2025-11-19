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
            // For truncated HTML with unclosed attributes, try to repair it first
            val repairedSnippet = repairTruncatedHtml(htmlSnippet)
            val snippetDoc = Jsoup.parseBodyFragment(repairedSnippet)
            val rootElement = snippetDoc.body().children().firstOrNull() 
                ?: return emptyList()
            
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
            // Skip structural matching if we have a unique ID selector - IDs are globally unique
            val structurallyMatchingElements = if (id.isNotBlank() && cleanedMatchingElements.size == 1) {
                logger.debug("Skipping structural match for unique ID selector: {}", baseSelector)
                cleanedMatchingElements.toList()
            } else {
                cleanedMatchingElements.filter { candidate ->
                    hasMatchingStructure(rootElement, candidate, htmlSnippet)
                }
            }
            
            logger.debug("Filtered {} candidates to {} structurally-matching elements in cleaned HTML", 
                cleanedMatchingElements.size, structurallyMatchingElements.size)
            
            if (structurallyMatchingElements.isEmpty()) {
                logger.warn("No structurally-matching elements found for snippet: {}", htmlSnippet)
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
     * Uses DOM-based comparison to handle formatting differences in HTML output.
     * 
     * @param snippetElement The root element from the LLM-provided HTML snippet (from cleaned HTML)
     * @param candidateElement The candidate element from cleaned HTML
     * @param rawSnippet The raw HTML snippet string (for debug logging)
     * @return true if structures match
     */
    private fun hasMatchingStructure(snippetElement: Element, candidateElement: Element, rawSnippet: String): Boolean {
        // Compare tag names
        if (snippetElement.tagName() != candidateElement.tagName()) {
            logger.debug(
                "Tag name mismatch: expected '{}', found '{}'",
                snippetElement.tagName(),
                candidateElement.tagName()
            )
            return false
        }
        
        // Compare attributes (ignoring order)
        if (!compareAttributes(snippetElement, candidateElement)) {
            logger.debug(
                "Attribute mismatch for <{}>: expected {}, found {}",
                snippetElement.tagName(),
                formatAttributesForLog(snippetElement),
                formatAttributesForLog(candidateElement)
            )
            return false
        }
        
        // Compare child structure recursively (up to depth limit)
        val maxDepth = 3
        if (!compareChildStructure(snippetElement, candidateElement, depth = 0, maxDepth = maxDepth)) {
//            logger.debug(
//                "Child structure mismatch for <{}>: expected {} children, found {} children",
//                snippetElement.tagName(),
//                snippetElement.children().size,
//                candidateElement.children().size
//            )
            return false
        }
        
        return true
    }
    
    /**
     * Compares attributes of two elements, ignoring order.
     * Only compares attributes that exist on the snippet element.
     * 
     * @param snippetElement The element from the LLM snippet
     * @param candidateElement The candidate element from cleaned HTML
     * @return true if all snippet attributes match
     */
    private fun compareAttributes(snippetElement: Element, candidateElement: Element): Boolean {
        val snippetAttrs = snippetElement.attributes()
        val candidateAttrs = candidateElement.attributes()
        
        // Check that all snippet attributes exist and match in the candidate
        for (attr in snippetAttrs) {
            val snippetValue = attr.value
            val candidateValue = candidateAttrs.get(attr.key)
            
            // Special handling for class attribute - compare as sets
            if (attr.key == "class") {
                val snippetClasses = snippetElement.classNames()
                val candidateClasses = candidateElement.classNames()
                if (snippetClasses != candidateClasses) {
                    return false
                }
            } else {
                // For other attributes, exact match required
                if (snippetValue != candidateValue) {
                    return false
                }
            }
        }
        
        return true
    }
    
    /**
     * Recursively compares child structure of two elements up to a depth limit.
     * 
     * @param snippetElement The element from the LLM snippet
     * @param candidateElement The candidate element from cleaned HTML
     * @param depth Current recursion depth
     * @param maxDepth Maximum recursion depth
     * @return true if child structures match
     */
    private fun compareChildStructure(
        snippetElement: Element,
        candidateElement: Element,
        depth: Int,
        maxDepth: Int
    ): Boolean {
        // Stop recursion at max depth
        if (depth >= maxDepth) {
            return true
        }
        
        val snippetChildren = snippetElement.children()
        val candidateChildren = candidateElement.children()
        
        // The snippet might be truncated, so candidate can have MORE children
        // but must have AT LEAST as many as the snippet shows
        if (candidateChildren.size < snippetChildren.size) {
            return false
        }
        
        // Compare each child in the snippet with corresponding child in candidate
        for (i in snippetChildren.indices) {
            val snippetChild = snippetChildren[i]
            val candidateChild = candidateChildren[i]
            
            // Compare tag names
            if (snippetChild.tagName() != candidateChild.tagName()) {
                logger.debug(
                    "Child tag mismatch at depth {}: expected '{}', found '{}'",
                    depth,
                    snippetChild.tagName(),
                    candidateChild.tagName()
                )
                return false
            }
            
            // Compare attributes
            if (!compareAttributes(snippetChild, candidateChild)) {
                logger.debug(
                    "Child attribute mismatch at depth {} for <{}>",
                    depth,
                    snippetChild.tagName()
                )
                return false
            }
            
            // Recursively compare children
            if (!compareChildStructure(snippetChild, candidateChild, depth + 1, maxDepth)) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Formats element attributes for debug logging.
     * 
     * @param element The element to format
     * @return A string representation of the element's attributes
     */
    private fun formatAttributesForLog(element: Element): String {
        val attrs = element.attributes()
        if (attrs.isEmpty()) {
            return "(no attributes)"
        }
        
        return attrs.joinToString(", ") { attr ->
            "${attr.key}=\"${attr.value}\""
        }
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
    
    /**
     * Attempts to repair truncated HTML that may have unclosed quotes or tags.
     * This handles edge cases where the LLM truncates mid-attribute or mid-tag.
     * 
     * @param html The potentially truncated HTML snippet
     * @return Repaired HTML that can be parsed by Jsoup
     */
    private fun repairTruncatedHtml(html: String): String {
        var repaired = html.trim()
        
        // Count quotes in attribute values - if odd number, close the quote
        val quoteCount = repaired.count { it == '"' }
        if (quoteCount % 2 == 1) {
            // Unclosed quote - close it
            repaired += "\""
        }
        
        // If the snippet doesn't end with >, try to close the opening tag
        if (!repaired.endsWith(">") && !repaired.endsWith("/>")) {
            repaired += ">"
        }
        
        return repaired
    }
}

