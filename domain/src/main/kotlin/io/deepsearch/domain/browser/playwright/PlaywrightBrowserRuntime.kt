package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserRuntime

/**
 * Playwright-backed browser runtime.
 * 
 * This represents a Playwright instance with a launched browser.
 * Runtimes are expensive to create and are pooled for reuse.
 * Each runtime can spawn multiple isolated browsers for concurrent link processing.
 */
class PlaywrightBrowserRuntime(
) : IBrowserRuntime {
    private val playwright: Playwright = Playwright.create()

    override suspend fun createBrowser(): IBrowser {
        return PlaywrightBrowser(playwright)
    }

    override suspend fun close() {
        playwright.close()
    }
}

