package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.Locator
import kotlinx.coroutines.Dispatchers
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    override fun navigate(url: String) {
        logger.debug("Navigate to {}", url)
        page.navigate(url)
        // Ensure baseline document readiness before any parsing calls
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
    }

    override fun takeScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = IBrowserPage.ImageMimeType.JPEG)
    }

    override fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking full-page screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
                fullPage = true
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = IBrowserPage.ImageMimeType.JPEG)
    }

    override suspend fun parse(): IBrowserPage.PageInformation {
        logger.debug("Parsing page information ...")

        val url = page.url()
        val title = page.title()
        val descriptionMeta = page.locator("meta[name=description], meta[property='og:description']")
        val description =
            if (descriptionMeta.count() > 0) descriptionMeta.first().getAttribute("content")?.trim() else null

        logger.debug(
            "Page meta extracted: url={}, title={}, descriptionLength={}",
            url,
            title,
            description?.length ?: 0
        )

        // TODO val iconMapping
        // using a js script, we need to extract all icons (e.g. <i> etc.) and place in a map of icon to base64, icons are typically reused in the website so the mapping must be unique
        // return the mapping here
        // then, we need to modify the method signature to take an IWebpageIconInterpreterAgent to convert the icon map into string interpretation
        // implement the agent according to how we invoke gemini to interpret images using google adk, reference existing agents for implementation
        // finally, we pass the icon - to - interpretation map into the textContentForExtraction script and convert icons to their interpreted string

        // Build a deterministic, indented text snapshot for downstream extraction with a single DOM-side pass.
        // Include common textual containers; exclude interactive elements (links/buttons) and images.
        val script = loadScript("scripts/textContentForExtraction.js")
        @Suppress("UNCHECKED_CAST")
        val textContentForExtraction = page.evaluate(script) as String

        logger.debug(
            "Extraction complete: totalChars={}",
            textContentForExtraction.length
        )

        return IBrowserPage.PageInformation(
            url = url,
            title = title,
            description = description,
            textContentForExtraction = textContentForExtraction
        )
    }

    private fun loadScript(path: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}