package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Playwright-backed browser instance created from a runtime.
 *
 * Each browser has its own mutex to ensure thread-safe access to the Playwright API,
 * allowing multiple browsers from the same runtime to operate concurrently.
 * Browsers are typically created per-link during web scraping.
 */
class PlaywrightBrowser(
    private val playwright: Playwright,
) : IBrowser {
    private val playwrightBrowser = playwright.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(true)
    )
    private val apiMutex: Mutex = Mutex()

    override suspend fun createContext(): IBrowserContext {
        val context = apiMutex.withLock { playwrightBrowser.newContext() }
        return PlaywrightBrowserContext(context, apiMutex)
    }

    override suspend fun close() {
        // Browser instance doesn't close the underlying Playwright browser,
        // only its contexts. The runtime owns the Playwright browser lifecycle.
    }
}