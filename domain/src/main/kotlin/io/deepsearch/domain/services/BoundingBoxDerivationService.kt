package io.deepsearch.domain.services

import io.deepsearch.domain.browser.IBrowserPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Result of deriving element-relative bounding boxes from a page snapshot.
 */
data class DerivedBoundingBoxes(
    val elementHtml: String,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>
)

/**
 * Service for deriving element-relative bounding boxes from page-level bounding boxes.
 * 
 * The page snapshot contains bounding boxes for all elements keyed by XPath relative to body.
 * Table interpretation needs bounding boxes relative to the table element itself.
 * This service transforms the coordinate system accordingly.
 * 
 * Note: Handles HTML normalization differences between browser and Jsoup.
 * Browsers and Jsoup may add implicit elements like <tbody> to tables,
 * which can cause XPath mismatches. This service tries multiple XPath
 * variations to find the correct bounding box match.
 */
interface IBoundingBoxDerivationService {
    /**
     * Derives element-relative bounding boxes from page-level bounding boxes.
     * 
     * @param cssSelector CSS selector for the target element
     * @param html Full page HTML (with data-ds-id attributes)
     * @param pageBoundingBoxes Bounding boxes from capturePageSnapshot (XPath from body -> BoundingBox)
     * @return DerivedBoundingBoxes containing element HTML and relative bounding boxes,
     *         or null if element not found
     */
    fun deriveElementBoundingBoxes(
        cssSelector: String,
        html: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): DerivedBoundingBoxes?
    
    /**
     * Batch version that derives bounding boxes for multiple elements.
     * More efficient than calling deriveElementBoundingBoxes multiple times
     * as it parses HTML only once.
     * 
     * @param cssSelectors List of CSS selectors for target elements
     * @param html Full page HTML
     * @param pageBoundingBoxes Bounding boxes from page snapshot
     * @return Map of CSS selector to DerivedBoundingBoxes (null if element not found)
     */
    fun deriveElementsBoundingBoxes(
        cssSelectors: List<String>,
        html: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): Map<String, DerivedBoundingBoxes?>
}

class BoundingBoxDerivationService : IBoundingBoxDerivationService {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    // Implicit table elements that may be added/removed by HTML normalization
    private val implicitTableElements = setOf("tbody", "thead", "tfoot")
    
    override fun deriveElementBoundingBoxes(
        cssSelector: String,
        html: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): DerivedBoundingBoxes? {
        return try {
            val doc = Jsoup.parse(html)
            deriveElementBoundingBoxesInternal(cssSelector, doc, pageBoundingBoxes)
        } catch (e: Exception) {
            logger.error("Failed to derive bounding boxes for selector '{}': {}", cssSelector, e.message)
            null
        }
    }
    
    override fun deriveElementsBoundingBoxes(
        cssSelectors: List<String>,
        html: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): Map<String, DerivedBoundingBoxes?> {
        if (cssSelectors.isEmpty()) {
            return emptyMap()
        }
        
        return try {
            val doc = Jsoup.parse(html)
            cssSelectors.associateWith { selector ->
                try {
                    deriveElementBoundingBoxesInternal(selector, doc, pageBoundingBoxes)
                } catch (e: Exception) {
                    logger.error("Failed to derive bounding boxes for selector '{}': {}", selector, e.message)
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse HTML for batch bounding box derivation: {}", e.message)
            cssSelectors.associateWith { null }
        }
    }
    
    private fun deriveElementBoundingBoxesInternal(
        cssSelector: String,
        doc: Document,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): DerivedBoundingBoxes? {
        // Find the target element
        val element = doc.selectFirst(cssSelector)
        if (element == null) {
            logger.warn("Element not found for selector: {}", cssSelector)
            return null
        }
        
        // Get the element's HTML
        val elementHtml = element.outerHtml()
        
        // Compute XPath from body to this element and find its bounding box
        val body = doc.body() ?: return null
        val (elementXPath, elementBBox) = findElementBoundingBox(element, body, pageBoundingBoxes)
            ?: run {
                logger.warn("Could not find bounding box for element with selector: {}", cssSelector)
                // Return HTML without bounding boxes - table interpretation can still work
                return DerivedBoundingBoxes(elementHtml, emptyMap())
            }
        
        logger.debug("Element XPath from body: {} (found bounding box)", elementXPath)
        
        // Find all descendant bounding boxes using flexible XPath matching
        val relativeBoundingBoxes = deriveRelativeBoundingBoxes(
            elementXPath, elementBBox, pageBoundingBoxes
        )
        
        logger.debug("Derived {} bounding boxes for element", relativeBoundingBoxes.size)
        
        return DerivedBoundingBoxes(elementHtml, relativeBoundingBoxes)
    }
    
    /**
     * Finds the element's bounding box by trying multiple XPath variations.
     * Handles cases where Jsoup's XPath doesn't match the browser's due to
     * implicit element normalization (e.g., tbody).
     * 
     * @return Pair of (xpath that matched, bounding box) or null if not found
     */
    private fun findElementBoundingBox(
        element: Element,
        body: Element,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): Pair<String, IBrowserPage.BoundingBox>? {
        // Try 1: Normal XPath (includes all elements)
        val normalXPath = computeRelativeXPath(element, body, skipImplicit = false)
        if (normalXPath != null) {
            pageBoundingBoxes[normalXPath]?.let { 
                return normalXPath to it 
            }
        }
        
        // Try 2: XPath skipping implicit table elements (tbody/thead/tfoot)
        // This handles cases where Jsoup added implicit elements that the browser didn't have
        val xpathSkippingImplicit = computeRelativeXPath(element, body, skipImplicit = true)
        if (xpathSkippingImplicit != null && xpathSkippingImplicit != normalXPath) {
            pageBoundingBoxes[xpathSkippingImplicit]?.let { 
                logger.debug("Found bounding box using XPath without implicit elements: {} -> {}", 
                    normalXPath, xpathSkippingImplicit)
                return xpathSkippingImplicit to it 
            }
        }
        
        // Try 3: Search for an XPath in pageBoundingBoxes that matches when we ignore implicit elements
        // This handles cases where the browser had implicit elements that Jsoup normalized away
        if (normalXPath != null) {
            val normalizedNormal = normalizeXPathForComparison(normalXPath)
            for ((xpath, bbox) in pageBoundingBoxes) {
                if (normalizeXPathForComparison(xpath) == normalizedNormal) {
                    logger.debug("Found bounding box using normalized XPath comparison: {} matches {}", 
                        normalXPath, xpath)
                    return xpath to bbox
                }
            }
        }
        
        return null
    }
    
    /**
     * Derives relative bounding boxes for all descendants of an element.
     * Uses flexible XPath matching to handle normalization differences.
     */
    private fun deriveRelativeBoundingBoxes(
        elementXPath: String,
        elementBBox: IBrowserPage.BoundingBox,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): MutableMap<String, IBrowserPage.BoundingBox> {
        val relativeBoundingBoxes = mutableMapOf<String, IBrowserPage.BoundingBox>()
        
        // Add the element itself with relative position (0,0 origin)
        relativeBoundingBoxes["."] = IBrowserPage.BoundingBox(
            left = 0.0,
            top = 0.0,
            right = elementBBox.right - elementBBox.left,
            bottom = elementBBox.bottom - elementBBox.top
        )
        
        val xpathPrefix = "$elementXPath/"
        val normalizedPrefix = normalizeXPathForComparison(elementXPath) + "/"
        
        for ((xpath, bbox) in pageBoundingBoxes) {
            var relativeXPath: String? = null
            
            // Direct prefix match
            if (xpath.startsWith(xpathPrefix)) {
                relativeXPath = "./" + xpath.removePrefix(xpathPrefix)
            } 
            // Normalized prefix match (handles tbody differences)
            else {
                val normalizedXPath = normalizeXPathForComparison(xpath)
                if (normalizedXPath.startsWith(normalizedPrefix)) {
                    // Use the normalized relative path
                    relativeXPath = "./" + normalizedXPath.removePrefix(normalizedPrefix)
                }
            }
            
            if (relativeXPath != null) {
                // Transform coordinates to be relative to the element
                val relativeBBox = IBrowserPage.BoundingBox(
                    left = bbox.left - elementBBox.left,
                    top = bbox.top - elementBBox.top,
                    right = bbox.right - elementBBox.left,
                    bottom = bbox.bottom - elementBBox.top
                )
                relativeBoundingBoxes[relativeXPath] = relativeBBox
            }
        }
        
        return relativeBoundingBoxes
    }
    
    /**
     * Normalizes an XPath by removing implicit table elements (tbody, thead, tfoot).
     * This allows comparison between XPaths from different HTML parsers.
     * 
     * Example: "./div[1]/table[1]/tbody[1]/tr[1]" -> "./div[1]/table[1]/tr[1]"
     */
    private fun normalizeXPathForComparison(xpath: String): String {
        if (!xpath.startsWith("./")) return xpath
        
        val parts = xpath.substring(2).split("/")
        val normalizedParts = parts.filter { part ->
            // Extract tag name from "tagname[index]"
            val tagName = part.substringBefore('[').lowercase()
            !implicitTableElements.contains(tagName)
        }
        
        return "./" + normalizedParts.joinToString("/")
    }
    
    /**
     * Computes the XPath of an element relative to a parent element.
     * Returns XPath in format: ./tagname[index]/tagname[index]/...
     * 
     * @param skipImplicit If true, skips implicit table elements (tbody/thead/tfoot)
     *                     when they are direct children of table elements
     */
    private fun computeRelativeXPath(
        element: Element, 
        parent: Element,
        skipImplicit: Boolean = false
    ): String? {
        if (element == parent) {
            return "."
        }
        
        val path = mutableListOf<String>()
        var current: Element? = element
        
        while (current != null && current != parent) {
            val tagName = current.tagName().lowercase()
            val parentTagName = current.parent()?.tagName()?.lowercase()
            
            // Skip implicit table elements if requested
            val shouldSkip = skipImplicit && 
                implicitTableElements.contains(tagName) && 
                parentTagName == "table"
            
            if (!shouldSkip) {
                // Count same-tag siblings before this element
                var index = 1
                var sibling = current.previousElementSibling()
                while (sibling != null) {
                    if (sibling.tagName().lowercase() == tagName) {
                        index++
                    }
                    sibling = sibling.previousElementSibling()
                }
                
                path.add(0, "$tagName[$index]")
            }
            
            current = current.parent()
        }
        
        // Check if we reached the parent
        return if (current == parent) {
            "./" + path.joinToString("/")
        } else {
            null
        }
    }
}

