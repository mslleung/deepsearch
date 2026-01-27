package io.deepsearch.domain.browser

import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Abstraction over a single browser page/tab that can be navigated and inspected.
 *
 * The page provides a human-oriented snapshot of what a user can see and do on the
 * current document. This snapshot is the "eye" for LLM agents to reason over.
 */
interface IBrowserPage {

    /**
     * Bounding box coordinates relative to an element's position.
     * Coordinates are relative to the queried element's top-left corner.
     */
    @Serializable
    data class BoundingBox(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double
    )

    suspend fun getUrl(): String

    /**
     * Navigates the current page to the given URL and waits for DOMContentLoaded.
     * This returns as soon as the HTML structure is ready, allowing fast HTML preview extraction.
     * Call [waitForLoad] after extracting HTML if you need to wait for full page load.
     * @param url Absolute or relative URL to open.
     */
    suspend fun navigate(url: String)

    /**
     * Wait for the page to fully load (load event fired).
     * Call this before operations that require all resources to be loaded (e.g., full markdown extraction).
     * 
     * Handles the race condition where the load event may have already fired before this method is called
     * by first checking document.readyState.
     */
    suspend fun waitForLoad()

    /**
     * Navigate to URL with pre-fetched HTML content using network interception.
     * 
     * The browser navigates to the URL, but the document request is intercepted and
     * fulfilled with the cached HTML. Other resources (images, CSS, JS) load normally.
     * 
     * This enables single-request processing: HTTP downloads HTML once, browser uses it.
     * 
     * @param url The URL to navigate to (for correct origin/base URL)
     * @param htmlBody The pre-fetched HTML content to serve to the browser
     */
    suspend fun navigateWithCachedHtml(url: String, htmlBody: ByteArray)

    /**
     * Takes a screenshot of the current viewport and returns the image bytes.
     */
    suspend fun takeScreenshot(): Screenshot

    /**
     * Takes a screenshot of the full page and returns the image bytes.
     */
    suspend fun takeFullPageScreenshot(): Screenshot

    suspend fun getFullHtml(): String

    /**
     * Takes a screenshot of the element matched by the provided XPath expression.
     */
    suspend fun getElementScreenshotByXPath(xpath: String): Screenshot

    /**
     * Takes a screenshot of the element matched by the provided CSS selector.
     */
    suspend fun getElementScreenshotByCssSelector(cssSelector: String): Screenshot

    /**
     * Returns the outer HTML of the element matched by the provided XPath expression.
     */
    suspend fun getElementHtmlByXPath(xpath: String): String

    /**
     * Returns the outer HTML of the element matched by the provided CSS selector.
     */
    suspend fun getElementHtmlByCssSelector(cssSelector: String): String

    /**
     * Click the first element matching the provided XPath selector.
     * Returns true if a matching element was found and clicked; false otherwise.
     */
    suspend fun clickByXPathSelector(xpath: String)

    /**
     * Remove the DOM element matched by the provided XPath selector from the document.
     * No-op if no element matches.
     */
    suspend fun removeElement(xpath: String)

    /**
     * Remove the DOM element matched by the provided CSS selector from the document.
     * No-op if no element matches.
     */
    suspend fun removeElementByCssSelector(cssSelector: String)

    /**
     * Remove multiple DOM elements matched by the provided CSS selectors in a single batch operation.
     * More efficient than calling removeElementByCssSelector multiple times.
     * No-op for selectors that don't match any elements.
     * 
     * @param selectors List of CSS selectors to match and remove
     */
    suspend fun removeElementsByCssSelectors(selectors: List<String>)

    /**
     * Check if an element matching the provided XPath selector exists in the document.
     * @param xpath XPath expression to match
     * @return true if at least one element matches; false otherwise
     */
    suspend fun elementExists(xpath: String): Boolean

    /**
     * Check if an element matching the provided CSS selector exists in the document.
     * @param cssSelector CSS selector to match
     * @return true if at least one element matches; false otherwise
     */
    suspend fun elementExistsByCssSelector(cssSelector: String): Boolean

    /**
     * Check if an element matching the provided CSS selector is visible.
     * @param cssSelector CSS selector to match
     * @return true if the element is visible; false otherwise
     */
    suspend fun isElementVisibleByCssSelector(cssSelector: String): Boolean

    /**
     * Check if an element matching the provided XPath selector is visible.
     * @param xpath XPath expression to match
     * @return true if the element is visible; false otherwise
     */
    suspend fun isElementVisibleByXPath(xpath: String): Boolean

    /**
     * Rendered web icon bitmap and metadata used for interpretation and caching.
     *
     * bytes: raw image bytes (typically WebP from canvas rendering).
     * mimeType: image mime type, defaults to WebP.
     * cssSelectors: list of CSS selectors for DOM nodes that render this icon image.
     */
    data class Icon(
        val bytes: ByteArray,
        val mimeType: ImageMimeType = ImageMimeType.WEBP,
        val cssSelectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    /**
     * Extract rendered icons from the current page.
     *
     * Extracts various icon types (i, svg, span with icon classes, etc.).
     * Each icon is rendered as a WebP and deduplicated by SHA-256 hash of the bytes.
     *
     * @return List of Icon containing bytes, mimeType, and CSS selectors.
     */
    suspend fun extractIcons(): List<Icon>

    /**
     * Rendered web image and metadata used for text extraction and caching.
     *
     * bytes: raw image bytes (WebP from canvas rendering, or PNG from Playwright screenshots).
     * mimeType: image mime type.
     * cssSelectors: list of CSS selectors for DOM nodes that contain this image.
     */
    data class WebImage(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
        val cssSelectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    /**
     * Extract images from the current page.
     *
     * Each image is captured as a screenshot and deduplicated by SHA-256 hash of the bytes.
     *
     * @return List of WebImage containing bytes, mimeType, and CSS selectors.
     */
    suspend fun extractImages(): List<WebImage>
    
    /**
     * Extract images from the current page using a pre-captured screenshot for fallback.
     * 
     * This is more efficient when the caller already has a screenshot (e.g., for vision-based agents)
     * as it avoids capturing a duplicate screenshot for fallback cropping.
     *
     * @param screenshot A pre-captured full-page screenshot to use for fallback
     * @return List of WebImage containing bytes, mimeType, and CSS selectors.
     */
    suspend fun extractImagesWithScreenshot(screenshot: Screenshot): List<WebImage>

    /**
     * Result of combined media extraction containing both icons and images.
     * Extracting both in a single call is more efficient than calling extractIcons() and extractImages() separately.
     */
    data class MediaExtractionResult(
        val icons: List<Icon>,
        val images: List<WebImage>,
        val failedImages: List<FailedImageInfo>
    )

    /**
     * Result of stable ID injection.
     * Contains counts of injected IDs by type.
     */
    data class StableIdInjectionResult(
        /** Number of structural/semantic elements injected (ds-element-N) */
        val elements: Int,
        /** Number of icon elements injected (ds-icon-N) */
        val icons: Int,
        /** Number of image elements injected (ds-image-N) */
        val images: Int
    )

    /**
     * Inject stable data-ds-id attributes on all elements the pipeline needs.
     * 
     * ID Format: ds-{type}-{id} where:
     * - type: element | icon | image
     * - id: Sequential integer (0, 1, 2, ...)
     * 
     * This method must be called BEFORE any extraction methods (capturePageSnapshot,
     * extractIcons, extractImages) to ensure all elements have stable IDs that can
     * be referenced later.
     * 
     * @return StableIdInjectionResult with counts of injected IDs by type
     */
    suspend fun injectStableIds(): StableIdInjectionResult

    /**
     * Information about an image that failed to be extracted via canvas rendering.
     * These images can be captured via screenshot fallback.
     */
    data class FailedImageInfo(
        val cssSelector: String,
        val reason: String
    )

    /**
     * Extract both icons and images from the current page in a single browser call.
     * This is more efficient than calling extractIcons() and extractImages() separately
     * as it reduces browser round-trips.
     *
     * @return MediaExtractionResult containing icons, successfully extracted images, and failed images for fallback
     */
    suspend fun extractMedia(): MediaExtractionResult

    /**
     * Pre-fetched page data for efficient parallel processing.
     * Captures all data needed by multiple agents in a single coordinated operation,
     * eliminating duplicate browser calls.
     */
    data class PageSnapshot(
        val html: String,
        val boundingBoxes: Map<String, BoundingBox>,
        val mediaExtractionResult: MediaExtractionResult
    )

    /**
     * Full page snapshot including metadata, captured in a single CDP call.
     * This is the optimized version that reduces CDP round-trips from 5 to 1.
     */
    data class FullPageSnapshot(
        val title: String,
        val description: String?,
        val url: String,
        val html: String,
        val boundingBoxes: Map<String, BoundingBox>,
        val mediaExtractionResult: MediaExtractionResult
    )

    /**
     * Page snapshot with metadata but without media extraction.
     * Used for semantic/table identification which only needs DOM structure.
     * 
     * Note: Hidden containers are NOT included. Use captureHiddenContainerBoundingBoxes()
     * separately as the last browser operation if hidden container analysis is needed.
     */
    data class PageSnapshotWithMetadata(
        val title: String,
        val description: String?,
        val url: String,
        val html: String,
        val boundingBoxes: Map<String, BoundingBox>
    )

    /**
     * Attribute injection specification for batch operations.
     */
    data class AttributeInjection(
        val cssSelector: String,
        val attributeName: String,
        val attributeValue: String
    )

    /**
     * Combined HTML and bounding boxes for table interpretation.
     */
    data class TableInterpretationData(
        val html: String,
        val boundingBoxes: Map<String, BoundingBox>
    )

    /**
     * Capture a snapshot of the current page state for parallel agent processing.
     * This fetches HTML, bounding boxes, icons, and images in one coordinated operation.
     * More efficient than having each agent fetch data independently.
     *
     * @return PageSnapshot containing all pre-fetched data
     */
    suspend fun captureSnapshot(): PageSnapshot

    /**
     * Capture a full page snapshot including metadata in a SINGLE CDP call.
     * This is the optimized version that reduces CDP round-trips from 5 to 1.
     * 
     * Returns: title, description, url, html, boundingBoxes, and media extraction results.
     */
    suspend fun captureFullSnapshot(): FullPageSnapshot

    /**
     * Capture a page snapshot with metadata but without media extraction.
     * This is faster than captureFullSnapshot() and suitable for semantic/table identification
     * which only needs DOM structure and bounding boxes.
     * 
     * Returns: title, description, url, html, boundingBoxes (no media).
     */
    suspend fun capturePageSnapshot(): PageSnapshotWithMetadata

    /**
     * Image screenshot payload and format information.
     */
    data class Screenshot(
        val bytes: ByteArray,
        val mimeType: ImageMimeType
    )

    suspend fun getTitle(): String

    suspend fun getDescription(): String?

    /**
     * Replacement instruction for XPath-targeted nodes.
     */
    data class XPathReplacementWithText(
        val xpath: String,
        val text: String?
    )

    /**
     * Replacement instruction for CSS selector-targeted nodes.
     */
    data class CssSelectorReplacementWithText(
        val cssSelector: String,
        val text: String?
    )

    /**
     * Replace multiple elements by XPath with text nodes in a single batch operation.
     * @param replacements List of XPathReplacement. If text is null, removes the elements.
     */
    suspend fun replaceElementsByXPathWithText(replacements: List<XPathReplacementWithText>)

    /**
     * Replace multiple elements by CSS selector with text nodes in a single batch operation.
     * @param replacements List of CssSelectorReplacementWithText. If text is null, removes the elements.
     */
    suspend fun replaceElementsByCssSelectorWithText(replacements: List<CssSelectorReplacementWithText>)

    /**
     * Extract text content from the page, excluding script and style tags.
     * Text nodes are traversed in document order.
     * @return Extracted text with one text node per line
     */
    suspend fun extractTextContent(): String

    /**
     * Extract text content from specific element, identified by their XPath.
     * Each popup's subtree is traversed similarly to extractTextContent, excluding script and style tags.
     * The resulting lines are joined with newlines.
     */
    suspend fun extractElementTextContent(elementXPath: String): String

    /**
     * Extract text content from specific element, identified by their CSS selector.
     * The element's subtree is traversed similarly to extractTextContent, excluding script and style tags.
     * The resulting lines are joined with newlines.
     */
    suspend fun extractElementTextContentByCssSelector(cssSelector: String): String

    /**
     * Get bounding boxes for all descendant elements within the element matched by the CSS selector.
     * Coordinates are relative to the matched element's top-left corner.
     * 
     * @param cssSelector CSS selector to match the parent element
     * @return Map of element identifiers (xpath relative to parent) to their bounding boxes
     */
    suspend fun getBoundingBoxesByCssSelector(cssSelector: String): Map<String, BoundingBox>

    /**
     * Inject an attribute into all elements matching the CSS selector.
     * This modifies the actual DOM of the webpage.
     * 
     * @param cssSelector CSS selector to match the target elements
     * @param attributeName Name of the attribute to inject (e.g., "data-ds-id")
     * @param attributeValue Value of the attribute to inject
     */
    suspend fun injectAttributeByCssSelector(cssSelector: String, attributeName: String, attributeValue: String)

    /**
     * Batch inject multiple attributes into elements matching CSS selectors.
     * Single CDP call for all injections - more efficient than multiple injectAttributeByCssSelector calls.
     * 
     * @param injections List of AttributeInjection containing cssSelector, attributeName, and attributeValue
     */
    suspend fun injectAttributesByCssSelectors(injections: List<AttributeInjection>)

    /**
     * Get table interpretation data (HTML + bounding boxes) in a single CDP call.
     * More efficient than calling getElementHtmlByCssSelector and getBoundingBoxesByCssSelector separately.
     * 
     * @param cssSelector CSS selector to match the table element
     * @return TableInterpretationData containing html and boundingBoxes
     */
    suspend fun getTableInterpretationData(cssSelector: String): TableInterpretationData

    /**
     * Extract text content from multiple elements in a single CDP call.
     * More efficient than calling extractElementTextContentByCssSelector multiple times.
     * 
     * @param selectors List of CSS selectors to match elements
     * @return Map of CSS selector to extracted text content
     */
    suspend fun extractElementsTextContentByCssSelectors(selectors: List<String>): Map<String, String>

    /**
     * Check if multiple elements exist by CSS selectors in a single CDP call.
     * More efficient than calling elementExistsByCssSelector multiple times.
     * 
     * @param selectors List of CSS selectors to check
     * @return Map of CSS selector to existence boolean
     */
    suspend fun elementsExistByCssSelectors(selectors: List<String>): Map<String, Boolean>

    /**
     * Get HTML of multiple elements by CSS selectors in a single CDP call.
     * More efficient than calling getElementHtmlByCssSelector multiple times.
     * 
     * @param selectors List of CSS selectors to fetch HTML for
     * @return Map of CSS selector to outer HTML (empty string if element not found)
     */
    suspend fun getElementsHtmlByCssSelectors(selectors: List<String>): Map<String, String>

    /**
     * Get table interpretation data (HTML + bounding boxes) for multiple tables in a single CDP call.
     * More efficient than calling getTableInterpretationData multiple times.
     * 
     * @param selectors List of CSS selectors for table elements
     * @return Map of CSS selector to TableInterpretationData
     */
    suspend fun getTablesInterpretationData(selectors: List<String>): Map<String, TableInterpretationData>

    /**
     * Bounding box data for a single hidden container.
     * 
     * Uses LOCAL IDs (data-ds-local) instead of global data-ds-id to avoid
     * React re-render issues. The containerHtml contains the full subtree
     * with embedded local IDs for server-side parsing.
     */
    data class HiddenContainerBoundingBoxData(
        /** Stable CSS selector to find this container in the original snapshot HTML */
        val containerLocator: String,
        /** Container outerHTML with data-ds-local attributes (for server-side parsing) */
        val containerHtml: String,
        /** Bounding box of the container itself */
        val containerBox: BoundingBox,
        /** Map of local element ID (ds-local-N) to bounding box */
        val elements: Map<String, BoundingBox>
    )

    /**
     * Icon extracted from hidden containers (visible after reveal).
     * These icons have data-ds-id from initial injection but were skipped
     * by extractIcons because they had zero dimensions when hidden.
     */
    data class HiddenIcon(
        /** Base64-encoded PNG of the icon */
        val base64: String,
        /** CSS selector using data-ds-id (same as main icon extraction) */
        val cssSelector: String
    )

    /**
     * Image extracted from hidden containers (visible after reveal).
     * These images have data-ds-id from initial injection but may have been
     * skipped by extractImages because they had zero dimensions or weren't loaded.
     */
    data class HiddenImage(
        /** Base64-encoded image data */
        val base64: String,
        /** MIME type of the image */
        val mimeType: String,
        /** CSS selector using data-ds-id (same as main image extraction) */
        val cssSelector: String
    )

    /**
     * Result of capturing bounding boxes for elements inside hidden containers.
     * Used for server-side table detection algorithm.
     */
    data class HiddenContainerBoundingBoxes(
        /** Data for each hidden container */
        val hiddenContainers: List<HiddenContainerBoundingBoxData>,
        /** Number of hidden containers found */
        val hiddenContainerCount: Int,
        /** Total elements captured across all containers */
        val totalElementsCaptured: Int,
        /** Icons extracted from hidden containers (now visible after reveal) */
        val hiddenIcons: List<HiddenIcon> = emptyList(),
        /** Images extracted from hidden containers (now visible after reveal) */
        val hiddenImages: List<HiddenImage> = emptyList()
    )

    /**
     * Capture bounding boxes for all elements inside hidden containers.
     * 
     * This method:
     * 1. Finds all hidden containers (accordions, tabs, etc.)
     * 2. Reveals them temporarily
     * 3. Captures bounding boxes for all elements with data-ds-id
     * 4. Restores the original hidden state
     * 
     * The returned data can be used for server-side table detection algorithm
     * without running complex analysis in the browser.
     * 
     * Must be called AFTER [injectStableIds] to ensure elements have data-ds-id attributes.
     * 
     * @return HiddenContainerBoundingBoxes containing bounding box data for each hidden container
     */
    suspend fun captureHiddenContainerBoundingBoxes(): HiddenContainerBoundingBoxes

    /**
     * Close this page and release associated resources.
     */
    suspend fun close()
}