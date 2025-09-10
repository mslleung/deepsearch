package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.Locator
import kotlinx.coroutines.Dispatchers
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
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
    private val tableIdentificationAgent: ITableIdentificationAgent,
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
    
        val screenshot = takeScreenshot()
        val tableInput = TableIdentificationInput(screenshot.bytes)
        val tableOutput = tableIdentificationAgent.generate(tableInput)
        val tableXPaths = tableOutput.tableXPaths
        logger.debug("Identified {} table xpaths: {}", tableXPaths.size, tableXPaths)
    
        val script = loadScript("scripts/textContentForExtraction.js")
        @Suppress("UNCHECKED_CAST")
        val textContentForExtraction = page.evaluate(script, tableXPaths) as String

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