package io.deepsearch.domain.browser

import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.leptonica.global.leptonica
import org.bytedeco.tesseract.TessBaseAPI
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes

/**
 * Abstraction over a single browser page/tab that can be navigated and inspected.
 *
 * The page provides a human-oriented snapshot of what a user can see and do on the
 * current document. This snapshot is the "eye" for LLM agents to reason over.
 */
interface IBrowserPage {
    companion object {
        /**
         * Tessdata path extracted from JAR resources to temporary directory.
         * Tesseract requires filesystem access to tessdata files, not JAR resources.
         */
        private val tessdataPath: String by lazy { extractTessdataToTempDirectory() }

        /**
         * Creates a new TessBaseAPI instance for OCR operations.
         * Each call creates a new instance to ensure thread safety.
         * Supports English, Chinese Simplified, and Chinese Traditional.
         */
        private fun createTessBaseAPI(): TessBaseAPI {
            return TessBaseAPI().apply {
                // Initialize with English, Chinese Simplified, and Chinese Traditional
                if (Init(tessdataPath, "eng+chi_sim+chi_tra") != 0) {
                    throw RuntimeException("Failed to initialize Tesseract OCR with tessdata path: $tessdataPath")
                }
            }
        }

        /**
         * Extracts tessdata files from JAR resources to a temporary directory.
         * Tesseract requires filesystem access to tessdata files, not JAR resources.
         */
        private fun extractTessdataToTempDirectory(): String {
            val tempDir = createTempDirectory("tessdata")
            tempDir.toFile().deleteOnExit()

            val tessdataFiles = listOf("eng.traineddata", "chi_sim.traineddata", "chi_tra.traineddata")

            for (fileName in tessdataFiles) {
                val resourcePath = "tessdata/$fileName"
                val resource = IBrowserPage::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: throw RuntimeException("Tessdata file not found: $resourcePath")

                val outputFile = tempDir / fileName
                outputFile.toFile().deleteOnExit()

                resource.use { input ->
                    outputFile.writeBytes(input.readBytes())
                }
            }

            return tempDir.toFile().absolutePath
        }
    }

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
     * Check if an element matching the provided XPath selector exists in the document.
     * @param xpath XPath expression to match
     * @return true if at least one element matches; false otherwise
     */
    suspend fun elementExists(xpath: String): Boolean

    /**
     * Rendered web icon bitmap and metadata used for interpretation and caching.
     *
     * bytes: raw image bytes (typically JPEG).
     * mimeType: image mime type, defaults to JPEG.
     * xPathSelectors: list of XPath selectors for DOM nodes that render this icon image.
     */
    data class Icon(
        val bytes: ByteArray,
        val mimeType: ImageMimeType = ImageMimeType.JPEG,
        val xPathSelectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
    }

    /**
     * Extract rendered icons from the current page.
     *
     * Extracts various icon types (i, svg, span with icon classes, etc.).
     * Each icon is rendered as a JPEG and deduplicated by SHA-256 hash of the bytes.
     *
     * @return List of Icon containing bytes, mimeType, and xPathSelectors.
     */
    suspend fun extractIcons(): List<Icon>

    /**
     * Rendered web image and metadata used for text extraction and caching.
     *
     * bytes: raw image bytes (typically JPEG).
     * mimeType: image mime type.
     * xPathSelectors: list of XPath selectors for DOM nodes that contain this image.
     */
    data class WebImage(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
        val xPathSelectors: List<String>
    ) {
        val bytesHash: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }

        /**
         * Detects if this image likely contains text using OCR.
         * Returns true if text is detected, false otherwise.
         */
        fun containsText(): Boolean {
            val imagePointer = BytePointer(*bytes)
            val pix = leptonica.pixReadMem(imagePointer, bytes.size.toLong())
            if (pix == null || pix.isNull) {
                return true
            }

            val tessBaseAPI = createTessBaseAPI()
            try {
                tessBaseAPI.SetImage(pix)
                val text = tessBaseAPI.GetUTF8Text()?.string
                return !text.isNullOrBlank() && text.trim().length > 2
            } finally {
                leptonica.pixDestroy(pix)
                tessBaseAPI.End()
            }
        }
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
     * Replace all elements matching the given CSS selector with a text node.
     * If text is null, remove the elements entirely.
     * @param cssSelector CSS selector to match elements
     * @param text Replacement text, or null to remove elements
     */
    suspend fun replaceElementsWithText(cssSelector: String, text: String?)

    /**
     * Replacement instruction for XPath-targeted nodes.
     */
    data class XPathReplacementWithText(
        val xpath: String,
        val text: String?
    )

    /**
     * Replace multiple elements by XPath with text nodes in a single batch operation.
     * @param replacements List of XPathReplacement. If text is null, removes the elements.
     */
    suspend fun replaceElementsByXPathWithText(replacements: List<XPathReplacementWithText>)

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