package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserRuntime
import io.deepsearch.domain.services.ICssSelectorConstructionService

/**
 * Playwright-backed browser runtime.
 * 
 * This represents a Playwright instance with a launched browser.
 * Runtimes are expensive to create and are pooled for reuse.
 * Each runtime can spawn multiple isolated browsers for concurrent link processing.
 */
class PlaywrightBrowserRuntime(
    private val cssSelectorConstructionService: ICssSelectorConstructionService
) : IBrowserRuntime {
    private val playwright: Playwright = Playwright.create()

    override suspend fun createBrowser(): IBrowser {
        return PlaywrightBrowser(playwright, cssSelectorConstructionService)
    }

    override suspend fun close() {
        playwright.close()
    }
}

