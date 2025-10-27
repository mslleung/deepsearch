package io.deepsearch.domain.browser

import io.deepsearch.domain.constants.ImageMimeType
import java.security.MessageDigest

/**
 * Abstraction over a single browser page/tab that can be navigated and inspected.
 *
 * The page provides a human-oriented snapshot of what a user can see and do on the
 * current document. This snapshot is the "eye" for LLM agents to reason over.
 */
interface IBrowserPage {

    suspend fun getUrl(): String

    /**
     * Navigates the current page to the given URL and waits for the default load state.
     * @param url Absolute or relative URL to open.
     */
    suspend fun navigate(url: String)

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
     * Check if an element matching the provided XPath selector exists in the document.
     * @param xpath XPath expression to match
     * @return true if at least one element matches; false otherwise
     */
    suspend fun elementExists(xpath: String): Boolean

    /**
     * Rendered web icon bitmap and metadata used for interpretation and caching.
     *
     * bytes: raw image bytes (typically WebP from canvas rendering).
     * mimeType: image mime type, defaults to WebP.
     * xPathSelectors: list of XPath selectors for DOM nodes that render this icon image.
     */
    data class Icon(
        val bytes: ByteArray,
        val mimeType: ImageMimeType = ImageMimeType.WEBP,
        val xPathSelectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    /**
     * Extract rendered icons from the current page.
     *
     * Extracts various icon types (i, svg, span with icon classes, etc.).
     * Each icon is rendered as a WebP and deduplicated by SHA-256 hash of the bytes.
     *
     * @return List of Icon containing bytes, mimeType, and xPathSelectors.
     */
    suspend fun extractIcons(): List<Icon>

    /**
     * Rendered web image and metadata used for text extraction and caching.
     *
     * bytes: raw image bytes (WebP from canvas rendering, or PNG from Playwright screenshots).
     * mimeType: image mime type.
     * xPathSelectors: list of XPath selectors for DOM nodes that contain this image.
     */
    data class WebImage(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
        val xPathSelectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    /**
     * Extract images from the current page.
     *
     * Each image is captured as a screenshot and deduplicated by SHA-256 hash of the bytes.
     *
     * @return List of WebImage containing bytes, mimeType, and xPathSelectors.
     */
    suspend fun extractImages(): List<WebImage>


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
}