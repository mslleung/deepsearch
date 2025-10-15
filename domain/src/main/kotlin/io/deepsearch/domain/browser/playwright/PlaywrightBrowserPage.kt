package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Playwright-backed implementation of a browser page.
 *
 * Uses Playwright locators and ARIA roles to build a human-oriented snapshot of the page,
 * keeping ARIA and DOM details internal to this adapter.
 */
class PlaywrightBrowserPage(
    private val page: Page
) : IBrowserPage {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    init {
        page.onConsoleMessage { consoleMessage ->
            val message = consoleMessage.text()
            when (val type = consoleMessage.type()) {
                "log", "info" -> logger.info("[JS Console] {}", message)
                "warn" -> logger.warn("[JS Console] {}", message)
                "error" -> logger.error("[JS Console] {}", message)
                "debug" -> logger.debug("[JS Console] {}", message)
                else -> logger.info("[JS Console] [{}] {}", type, message)
            }
        }
    }

    override suspend fun getUrl(): String = page.url()

    /**
     * Navigate to a new URL and wait for default load state.
     */
    override suspend fun navigate(url: String) {
        logger.debug("Navigate to {}", url)
        page.navigate(url)
        // Ensure baseline document readiness before any parsing calls
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
    }

    override suspend fun takeScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking full-page screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
                fullPage = true
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun getFullHtml(): String {
        return page.content()
    }

    override suspend fun getElementScreenshotByXPath(xpath: String): IBrowserPage.Screenshot {
        logger.debug("Taking element screenshot by XPath: {}", xpath)
        val locator = page.locator("xpath=$xpath")
        // When XPath matches a chain (target + ancestors), select the leaf-most node
        val target = locator.last()
        val bytes = target.screenshot(
            Locator.ScreenshotOptions().apply { type = ScreenshotType.JPEG }
        )
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun getElementHtmlByXPath(xpath: String): String {
        logger.debug("Getting element outerHTML by XPath: {}", xpath)
        val locator = page.locator("xpath=$xpath")
        val target = locator.last()
        return target.evaluate("el => el.outerHTML") as String
    }

    override suspend fun clickByXPathSelector(xpath: String) {
        logger.debug("Click by XPath selector: {}", xpath)
        val locator = page.locator("xpath=$xpath")
        val target = locator.last()
        target.click()
    }

    override suspend fun removeElement(xpath: String) {
        logger.debug("Remove element by XPath: {}", xpath)
        val locator = page.locator("xpath=$xpath")
        val count = locator.count()

        if (count == 0) {
            logger.warn("No element found at XPath: {}", xpath)
            return
        }

        if (count > 1) {
            logger.debug("Multiple elements ({}) found at XPath: {}, removing all", count, xpath)
        }

        // Remove all matching elements using JavaScript
        locator.evaluateAll("elements => elements.forEach(element => element.remove())")

        logger.debug("Successfully removed {} element(s) at XPath: {}", count, xpath)
    }

    override suspend fun elementExists(xpath: String): Boolean {
        val locator = page.locator("xpath=$xpath")
        val count = locator.count()
        return count > 0
    }

    @Serializable
    private data class IconResult(val base64: String, val xPathSelectors: List<String>)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun extractIcons(): List<IBrowserPage.Icon> {
        logger.debug("Extracting icons via evaluate()")
        val extractIconJsonRaw = page.evaluate(loadScript("out/extractIcons.js")) as String

        val decoded = Json.decodeFromString<List<IconResult>>(extractIconJsonRaw)
        val results = decoded.map { result ->
            val bytes = Base64.decode(result.base64)
            IBrowserPage.Icon(
                bytes = bytes,
                mimeType = ImageMimeType.WEBP,
                xPathSelectors = result.xPathSelectors
            )
        }

        logger.debug("extractIcons produced {} unique icons", results.size)
        return results
    }

    @Serializable
    private data class ImageResult(val base64: String, val xPathSelectors: List<String>)
    
    @Serializable
    private data class FailedImage(val xPath: String, val reason: String)
    
    @Serializable
    private data class ImageExtractionResult(
        val successful: List<ImageResult>,
        val failed: List<FailedImage>
    )

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun extractImages(): List<IBrowserPage.WebImage> {
        logger.debug("Extracting images via evaluate()")
        val extractImageJsonRaw = page.evaluate(loadScript("out/extractImages.js")) as String
        val decoded = Json.decodeFromString<ImageExtractionResult>(extractImageJsonRaw)
        
        val results = mutableListOf<IBrowserPage.WebImage>()
        
        // Process successful canvas-based extractions
        decoded.successful.forEach { result ->
            val bytes = Base64.decode(result.base64)
            results.add(
                IBrowserPage.WebImage(
                    bytes = bytes,
                    mimeType = ImageMimeType.WEBP,
                    xPathSelectors = result.xPathSelectors
                )
            )
        }
        
        // Process failed images using screenshot fallback
        if (decoded.failed.isNotEmpty()) {
            logger.info("Processing {} failed images using screenshot fallback", decoded.failed.size)
            decoded.failed.forEach { failedImage ->
                try {
                    val screenshot = getElementScreenshotByXPath(failedImage.xPath)
                    results.add(
                        IBrowserPage.WebImage(
                            bytes = screenshot.bytes,
                            mimeType = screenshot.mimeType,
                            xPathSelectors = listOf(failedImage.xPath)
                        )
                    )
                    logger.debug("Successfully captured screenshot for failed image at {}", failedImage.xPath)
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot for image at {}: {}", failedImage.xPath, e.message)
                }
            }
        }

        logger.debug("extractImages produced {} unique images ({} canvas-based, {} screenshot-based)", 
            results.size, decoded.successful.size, decoded.failed.size)
        return results
    }


    override suspend fun getTitle(): String {
        return page.title()
    }

    override suspend fun getDescription(): String? {
        val descriptionMeta = page.locator("meta[name=description], meta[property='og:description']")
        val description =
            if (descriptionMeta.count() > 0) descriptionMeta.first().getAttribute("content")?.trim() else null
        return description
    }

    override suspend fun replaceElementsWithText(cssSelector: String, text: String?) {
        logger.debug("Replace elements by CSS selector: {} with text: {}", cssSelector, text)

        if (text != null) {
            page.evaluate(
                """
                (selector, replacement) => {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(el => {
                        const textNode = document.createTextNode(replacement);
                        el.replaceWith(textNode);
                    });
                }
            """, mapOf("selector" to cssSelector, "replacement" to text)
            )
        } else {
            page.evaluate(
                """
                (selector) => {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(el => el.remove());
                }
            """, cssSelector
            )
        }
    }

    override suspend fun replaceElementsByXPathWithText(replacements: List<IBrowserPage.XPathReplacementWithText>) {
        if (replacements.isEmpty()) {
            return
        }

        logger.debug("Replace {} XPath elements with text", replacements.size)

        // Convert replacements to a format that can be serialized for JavaScript
        val replacementsData = replacements.map { 
            mapOf("xpath" to it.xpath, "text" to it.text)
        }

        // Perform all replacements in a single evaluate call for optimal performance
        page.evaluate(
            """
            (replacements) => {
                for (const replacement of replacements) {
                    const xpath = replacement.xpath;
                    const text = replacement.text;
                    
                    // Evaluate XPath to get all matching nodes
                    const xpathResult = document.evaluate(
                        xpath,
                        document,
                        null,
                        XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
                        null
                    );
                    
                    const count = xpathResult.snapshotLength;
                    
                    if (count === 0) {
                        continue;
                    }
                    
                    // When XPath matches multiple nodes (target + ancestors), select the leaf-most node
                    const target = xpathResult.snapshotItem(count - 1);
                    
                    if (text !== null && text !== undefined) {
                        // Replace element with text node
                        const parent = target.parentNode;
                        if (!parent || parent.nodeType === Node.DOCUMENT_NODE) {
                            continue;
                        }
                        const textNode = document.createTextNode(text);
                        parent.replaceChild(textNode, target);
                    } else {
                        // Remove element
                        target.remove();
                    }
                }
            }
            """, replacementsData
        )
    }

    override suspend fun extractTextContent(): String {
        logger.debug("Extracting text content from page")

        return page.evaluate(
            """
            () => {
                const result = [];
                const stack = [document.body];
                
                // Elements that don't generally contain meaningful text for retrieval
                const excludeTags = new Set([
                    'script', 'style', 'noscript', 'button', 'iframe', 'nav', 'header', 'footer',
                    'aside', 'link', 'meta'
                ]);
                
                while (stack.length > 0) {
                    const node = stack.pop();
                    
                    if (node.nodeType === Node.TEXT_NODE) {
                        const text = node.textContent.trim();
                        if (text.length > 0) {
                            result.push(text);
                        }
                    } else if (node.nodeType === Node.ELEMENT_NODE) {
                        const tagName = node.tagName.toLowerCase();
                        if (!excludeTags.has(tagName)) {
                            const children = Array.from(node.childNodes);
                            for (let i = children.length - 1; i >= 0; i--) {
                                stack.push(children[i]);
                            }
                        }
                    }
                }
                
                return result.join('\n');
            }
        """
        ) as String
    }

    override suspend fun extractElementTextContent(elementXPath: String): String {
        logger.debug("Extracting text content from {}", elementXPath)

        return page.evaluate(
            """
            (xpath) => {
                const collectText = (root) => {
                    const result = [];
                    const stack = [root];
                    
                    // Elements that don't generally contain meaningful text for retrieval
                    const excludeTags = new Set([
                        'script', 'style', 'noscript', 'button', 'iframe', 'nav', 'header', 'footer',
                        'aside', 'link', 'meta'
                    ]);
                    
                    while (stack.length > 0) {
                        const node = stack.pop();
                        if (node.nodeType === Node.TEXT_NODE) {
                            const text = node.textContent.trim();
                            if (text.length > 0) {
                                result.push(text);
                            }
                        } else if (node.nodeType === Node.ELEMENT_NODE) {
                            const tagName = node.tagName.toLowerCase();
                            if (!excludeTags.has(tagName)) {
                                const children = Array.from(node.childNodes);
                                for (let i = children.length - 1; i >= 0; i--) {
                                    stack.push(children[i]);
                                }
                            }
                        }
                    }
                    return result;
                };

                const xpathResult = document.evaluate(
                    xpath,
                    document,
                    null,
                    XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
                    null
                );

                const count = xpathResult.snapshotLength;
                if (count === 0) {
                    return '';
                }
                const target = xpathResult.snapshotItem(count - 1);
                const result = collectText(target);
                return result.join('\n');
            }
            """,
            elementXPath
        ) as String
    }

    private fun loadScript(path: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}