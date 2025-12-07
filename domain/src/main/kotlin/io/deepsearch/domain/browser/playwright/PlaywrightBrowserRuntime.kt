package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserRuntime
import kotlinx.coroutines.sync.Mutex

/**
 * Playwright-backed browser runtime.
 * 
 * This represents a Playwright instance with a launched browser.
 * Runtimes are expensive to create and are pooled for reuse.
 * 
 * Threading: The apiMutex serializes ALL Playwright API calls through this runtime.
 * Playwright's Java client uses a single connection to communicate with the browser,
 * and concurrent messages can corrupt internal state. The mutex must be at the runtime
 * level (not browser/context level) to ensure all API calls are serialized.
 */
class PlaywrightBrowserRuntime : IBrowserRuntime {
    private val playwright: Playwright = Playwright.create()
    
    /**
     * Mutex to serialize all Playwright API calls through this runtime.
     * Shared with all browsers, contexts, and pages created from this runtime.
     */
    private val apiMutex: Mutex = Mutex()

    override suspend fun createBrowser(): IBrowser {
        return PlaywrightBrowser(playwright, apiMutex)
    }

    override suspend fun close() {
        playwright.close()
    }
}

