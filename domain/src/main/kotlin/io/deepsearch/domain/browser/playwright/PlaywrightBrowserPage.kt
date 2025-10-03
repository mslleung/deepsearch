package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import kotlinx.coroutines.Dispatchers
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Playwright-backed implementation of a browser page.
 *
 * Uses Playwright locators and ARIA roles to build a human-oriented snapshot of the page,
 * keeping ARIA and DOM details internal to this adapter.
 */
class PlaywrightBrowserPage(
    private val page: Page,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : IBrowserPage {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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
            logger.warn("Multiple elements ({}) found at XPath: {}, removing first", count, xpath)
        }

        // Get the first matching element and remove it using JavaScript
        val target = locator.first()
        target.evaluate("element => element.remove()")

        logger.debug("Successfully removed element at XPath: {}", xpath)
    }

    @Serializable
    private data class IconResult(val base64: String, val xPathSelectors: List<String>)

    override suspend fun extractIcons(): List<IBrowserPage.Icon> {
        logger.debug("Extracting icons via evaluate()")
        val extractIconJsonRaw = page.evaluate(loadScript("out/extractIcons.js")) as String

        val decoded = Json.decodeFromString<List<IconResult>>(extractIconJsonRaw)
        val results = decoded.map { result ->
            val bytes = Base64.getDecoder().decode(result.base64)
            IBrowserPage.Icon(
                bytes = bytes,
                mimeType = ImageMimeType.JPEG,
                xPathSelectors = result.xPathSelectors
            )
        }

        logger.debug("extractIcons produced {} unique icons", results.size)
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

        page.evaluate(
            """
            (replacements) => {
                replacements.forEach(({ xpath, text }) => {
                    const result = document.evaluate(xpath, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                    for (let i = 0; i < result.snapshotLength; i++) {
                        const el = result.snapshotItem(i);
                        if (text !== null) {
                            const textNode = document.createTextNode(text);
                            el.replaceWith(textNode);
                        } else {
                            el.remove();
                        }
                    }
                });
            }
        """, replacements.map { r -> mapOf("xpath" to r.xpath, "text" to r.text) }
        )
    }

    override suspend fun extractTextContent(): String {
        logger.debug("Extracting text content from page")

        return page.evaluate(
            """
            () => {
                const result = [];
                const stack = [document.body];
                
                while (stack.length > 0) {
                    const node = stack.pop();
                    
                    if (node.nodeType === Node.TEXT_NODE) {
                        const text = node.textContent.trim();
                        if (text.length > 0) {
                            result.push(text);
                        }
                    } else if (node.nodeType === Node.ELEMENT_NODE) {
                        const tagName = node.tagName.toLowerCase();
                        if (tagName !== 'script' && tagName !== 'style') {
                            const children = Array.from(node.childNodes);
                            for (let i = children.length - 1; i >= 0; i--) {
                                stack.push(children[i]);
                            }
                        }
                    }
                }
                
                return result.join('\\n');
            }
        """
        ) as String
    }

    private fun loadScript(path: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}