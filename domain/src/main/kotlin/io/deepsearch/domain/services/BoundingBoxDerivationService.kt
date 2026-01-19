package io.deepsearch.domain.services

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.StableElementId
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
 * The page snapshot contains bounding boxes for all elements keyed by data-ds-id.
 * Table interpretation needs bounding boxes relative to the table element itself.
 * This service transforms the coordinate system accordingly.
 */
interface IBoundingBoxDerivationService {
    /**
     * Derives element-relative bounding boxes from page-level bounding boxes.
     * 
     * @param cssSelector CSS selector for the target element (should be a data-ds-id selector)
     * @param html Full page HTML (with data-ds-id attributes)
     * @param pageBoundingBoxes Bounding boxes from capturePageSnapshot (data-ds-id -> BoundingBox)
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
    
    /**
     * Derives relative bounding boxes for child elements given parent and child IDs.
     * 
     * @param parentId The stable element ID of the parent element
     * @param childIds The stable element IDs of the child elements
     * @param absoluteBoundingBoxes Map of data-ds-id string values to absolute bounding boxes
     * @return Map of child data-ds-id string values to relative bounding boxes
     */
    fun deriveRelativeBoundingBoxes(
        parentId: StableElementId,
        childIds: List<StableElementId>,
        absoluteBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): Map<String, IBrowserPage.BoundingBox>
}

class BoundingBoxDerivationService : IBoundingBoxDerivationService {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
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
    
    override fun deriveRelativeBoundingBoxes(
        parentId: StableElementId,
        childIds: List<StableElementId>,
        absoluteBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): Map<String, IBrowserPage.BoundingBox> {
        val parentBbox = absoluteBoundingBoxes[parentId.stringValue] ?: return emptyMap()
        
        return childIds.mapNotNull { childId ->
            val childBbox = absoluteBoundingBoxes[childId.stringValue] ?: return@mapNotNull null
            childId.stringValue to IBrowserPage.BoundingBox(
                left = childBbox.left - parentBbox.left,
                top = childBbox.top - parentBbox.top,
                right = childBbox.right - parentBbox.left,
                bottom = childBbox.bottom - parentBbox.top
            )
        }.toMap()
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
        
        // Get the element's data-ds-id
        val elementDsId = element.attr("data-ds-id")
        if (elementDsId.isBlank()) {
            logger.warn("Element has no data-ds-id attribute for selector: {}", cssSelector)
            // Return HTML without bounding boxes - table interpretation can still work
            return DerivedBoundingBoxes(elementHtml, emptyMap())
        }
        
        // Get the element's absolute bounding box
        val elementBBox = pageBoundingBoxes[elementDsId]
        if (elementBBox == null) {
            logger.warn("No bounding box found for data-ds-id: {}", elementDsId)
            return DerivedBoundingBoxes(elementHtml, emptyMap())
        }
        
        // Find all descendant bounding boxes and compute relative coordinates
        val relativeBoundingBoxes = deriveRelativeBoundingBoxesFromElement(
            element, elementBBox, pageBoundingBoxes
        )
        
        logger.debug("Derived {} bounding boxes for element with data-ds-id: {}", 
            relativeBoundingBoxes.size, elementDsId)
        
        return DerivedBoundingBoxes(elementHtml, relativeBoundingBoxes)
    }
    
    /**
     * Derives relative bounding boxes for all descendants of an element.
     * Bounding boxes are keyed by the data-ds-id of each descendant.
     */
    private fun deriveRelativeBoundingBoxesFromElement(
        element: Element,
        elementBBox: IBrowserPage.BoundingBox,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): Map<String, IBrowserPage.BoundingBox> {
        val relativeBoundingBoxes = mutableMapOf<String, IBrowserPage.BoundingBox>()
        
        // Add the element itself with relative position (0,0 origin)
        val elementDsId = element.attr("data-ds-id")
        if (elementDsId.isNotBlank()) {
            relativeBoundingBoxes[elementDsId] = IBrowserPage.BoundingBox(
                left = 0.0,
                top = 0.0,
                right = elementBBox.right - elementBBox.left,
                bottom = elementBBox.bottom - elementBBox.top
            )
        }
        
        // Find all descendants with data-ds-id
        for (descendant in element.select("[data-ds-id]")) {
            val dsId = descendant.attr("data-ds-id")
            if (dsId.isBlank() || dsId == elementDsId) continue
            
            val descendantBBox = pageBoundingBoxes[dsId] ?: continue
            
            // Transform coordinates to be relative to the element
            relativeBoundingBoxes[dsId] = IBrowserPage.BoundingBox(
                left = descendantBBox.left - elementBBox.left,
                top = descendantBBox.top - elementBBox.top,
                right = descendantBBox.right - elementBBox.left,
                bottom = descendantBBox.bottom - elementBBox.top
            )
        }
        
        return relativeBoundingBoxes
    }
}
