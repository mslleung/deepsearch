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
     * Returns the outer HTML of the element matched by the provided XPath expression.
     */
    suspend fun getElementHtmlByXPath(xpath: String): String

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
     * Rendered web icon bitmap and metadata used for interpretation and caching.
     *
     * bytes: raw image bytes (typically JPEG).
     * mimeType: image mime type, defaults to JPEG.
     * selectors: list of CSS selectors for DOM nodes that render this icon image.
     */
    data class Icon(
        val bytes: ByteArray,
        val mimeType: ImageMimeType = ImageMimeType.JPEG,
        val selectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    /**
     * Extract rendered icons from the current page.
     *
     * Phase 1 focuses on <i> elements only. Each icon is rendered as a JPEG and deduplicated
     * by SHA-256 hash of the bytes.
     *
     * @return List of IconBitmap containing bytes, and mimeType.
     */
    suspend fun extractIcons(): List<Icon>

    /**
     * Image screenshot payload and format information.
     */
    data class Screenshot(
        val bytes: ByteArray,
        val mimeType: ImageMimeType
    )

    suspend fun getTitle(): String

    suspend fun getDescription(): String?

}