package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import kotlinx.coroutines.Dispatchers
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.LoadState
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.WebIconBitmap
import io.deepsearch.domain.utils.ImageHash
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
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override fun takeFullPageScreenshot(): IBrowserPage.Screenshot {
        logger.debug("Taking full-page screenshot ...")
        val bytes = page.screenshot(
            Page.ScreenshotOptions().apply {
                type = ScreenshotType.JPEG
                fullPage = true
            })
        return IBrowserPage.Screenshot(bytes = bytes, mimeType = ImageMimeType.JPEG)
    }

    override suspend fun extractIcons(): List<WebIconBitmap> {
        logger.debug("Extracting icons")
        val results = mutableListOf<WebIconBitmap>()
        val seenHashes = mutableSetOf<String>()

        val iLocator = page.locator("i")
        val total = iLocator.count()

        for (idx in 0 until total) {
            val nth = iLocator.nth(idx)
            try {
                val box = nth.boundingBox()
                if (box == null || box.width <= 0 || box.height <= 0) {
                    continue
                }
                val bytes = nth.screenshot(
                    Locator.ScreenshotOptions().apply {
                        type = ScreenshotType.JPEG
                        quality = 100
                    }
                )
                if (bytes.isEmpty()) continue

                val hash = ImageHash.hash(bytes)
                if (seenHashes.contains(hash)) {
                    continue
                }

                val classes = nth.getAttribute("class")
                val selectorHint = buildString {
                    append("i")
                    if (!classes.isNullOrBlank()) {
                        val short = classes.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(3)
                        if (short.isNotEmpty()) {
                            append(".")
                            append(short.joinToString("."))
                        }
                    }
                    append("::nth=")
                    append(idx)
                }

                results.add(
                    WebIconBitmap(
                        selector = selectorHint,
                        imageBytesHash = hash,
                        bytes = bytes,
                        mimeType = ImageMimeType.JPEG
                    )
                )
                seenHashes.add(hash)
            } catch (t: Throwable) {
                logger.debug("Skipping icon {} due to error: {}", idx, t.message)
            }
        }

        logger.debug("Extracted {} unique icons (from {})", results.size, total)
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