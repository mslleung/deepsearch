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
        if (locator.count() > 0) {
            val target = locator.last()
            target.evaluate("el => el.remove()")
        }
    }

    @Serializable
    private data class IconResult(val base64: String, val selectors: List<String>)

    override suspend fun extractIcons(): List<IBrowserPage.Icon> {
        logger.debug("Extracting icons via evaluate()")
        val extractIconJsonRaw = page.evaluate(loadScript("out/extractIcons.js")) as String

        val decoded = Json.decodeFromString<List<IconResult>>(extractIconJsonRaw)
        val results = decoded.map { result ->
            val bytes = Base64.getDecoder().decode(result.base64)
            IBrowserPage.Icon(
                bytes = bytes,
                mimeType = ImageMimeType.JPEG,
                selectors = result.selectors
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

    private fun loadScript(path: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}