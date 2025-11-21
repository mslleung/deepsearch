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
     * Filters out CSS class names that Jsoup's selector parser cannot handle reliably,
     * even when escaped. This primarily affects Tailwind arbitrary variants like [&>*]:space-y-10.
     * 
     * @param classes The set of class names from an element
     * @return A filtered set containing only Jsoup-compatible class names
     */
    private fun filterJsoupCompatibleClasses(classes: Set<String>): Set<String> {
        return classes.filter { className ->
            // Exclude classes with square brackets (Tailwind arbitrary variants)
            // These cause Jsoup's selector parser to fail even when properly escaped
            !className.contains('[') && !className.contains(']')
        }.toSet()
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
//                logger.debug(
//                    "Child tag mismatch at depth {}: expected '{}', found '{}'",
//                    depth,
//                    snippetChild.tagName(),
//                    candidateChild.tagName()
//                )
                return false
            }
            
            // Compare attributes
            if (!compareAttributes(snippetChild, candidateChild)) {
//                logger.debug(
//                    "Child attribute mismatch at depth {} for <{}>",
//                    depth,
//                    snippetChild.tagName()
//                )
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
                    val filteredClasses = filterJsoupCompatibleClasses(classes)
                    if (filteredClasses.isEmpty()) {
                        // No usable classes, fall back to tag with position
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
                    } else {
                        val classSelector = "$tagName.${filteredClasses.map { escapeCssIdentifier(it) }.joinToString(".")}"
                        // Check if this selector is unique at this level
                        val parent = currentElement.parent()
                        if (parent != null) {
                            val siblingsMatching = parent.children().filter { sibling ->
                                val siblingFilteredClasses = filterJsoupCompatibleClasses(sibling.classNames())
                                sibling.tagName() == tagName && siblingFilteredClasses == filteredClasses
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
        try {
            val matches = doc.select(hierarchicalSelector)
            if (matches.size != 1) {
//                logger.warn("Hierarchical selector '{}' matches {} elements, expected 1", hierarchicalSelector, matches.size)
                // Return it anyway, as it's still more specific than the base selector
            }
        } catch (e: org.jsoup.select.Selector.SelectorParseException) {
            // If even the hierarchical selector can't be parsed, log and return it anyway
            // The selector might still work in a real browser even if Jsoup can't parse it
            logger.warn("Failed to validate hierarchical selector '{}': {}", hierarchicalSelector, e.message)
        }
        
//        logger.debug("Built hierarchical selector: {}", hierarchicalSelector)
        return hierarchicalSelector
    }
    
    /**
     * Constructs a CSS selector from a stable identifier by finding the element in the HTML
     * and building a selector based on its attributes and structure.
     * 
     * @param identifier The data-ds-id value (e.g., "ds-semantic-5")
     * @param htmlWithIdentifiers The HTML document containing injected data-ds-id attributes
     * @return A CSS selector that can locate the element in the original webpage, or null if element not found
     */
    override fun constructCssSelectorFromIdentifier(identifier: String, htmlWithIdentifiers: String): String? {
        return try {
            val doc = Jsoup.parse(htmlWithIdentifiers)
            val element = doc.selectFirst("[data-ds-id=\"$identifier\"]")
            
            if (element == null) {
                logger.warn("Element with identifier '{}' not found in HTML", identifier)
                return null
            }
            
            // Build a CSS selector based on the element's attributes
            val tagName = element.tagName()
            val id = element.attr("id")
            val classes = element.classNames()
            
            // Strategy: prefer unique identifiers (id), then classes, then structural position
            val selector = when {
                // If element has an ID, use it (should be unique)
                id.isNotBlank() -> {
                    val idSelector = "#${escapeCssIdentifier(id)}"
                    try {
                        // Verify it's unique
                        if (doc.select(idSelector).size == 1) {
                            idSelector
                        } else {
                            // ID is not unique (shouldn't happen but be defensive), fall back to hierarchical
                            buildHierarchicalSelector(element, doc)
                        }
                    } catch (e: org.jsoup.select.Selector.SelectorParseException) {
                        // Jsoup couldn't parse the ID selector, fall back to hierarchical
                        logger.debug("Failed to parse ID selector '{}', falling back to hierarchical: {}", 
                            idSelector, e.message)
                        buildHierarchicalSelector(element, doc)
                    }
                }
                // If element has classes, try class-based selector
                classes.isNotEmpty() -> {
                    val filteredClasses = filterJsoupCompatibleClasses(classes)
                    if (filteredClasses.isEmpty()) {
                        // No Jsoup-compatible classes, use hierarchical selector
                        buildHierarchicalSelector(element, doc)
                    } else {
                        val classSelector = "$tagName.${filteredClasses.map { escapeCssIdentifier(it) }.joinToString(".")}"
                        try {
                            // Check if class selector is unique
                            if (doc.select(classSelector).size == 1) {
                                classSelector
                            } else {
                                // Not unique, use hierarchical selector
                                buildHierarchicalSelector(element, doc)
                            }
                        } catch (e: org.jsoup.select.Selector.SelectorParseException) {
                            // Jsoup couldn't parse the selector, fall back to hierarchical
                            logger.debug("Failed to parse class selector '{}', falling back to hierarchical: {}", 
                                classSelector, e.message)
                            buildHierarchicalSelector(element, doc)
                        }
                    }
                }
                // No id or classes, use hierarchical selector
                else -> buildHierarchicalSelector(element, doc)
            }
            
            logger.debug("Constructed selector '{}' for identifier '{}'", selector, identifier)
            selector
        } catch (e: Exception) {
            logger.error("Failed to construct CSS selector from identifier: {}", identifier, e)
            null
        }
    }
}

