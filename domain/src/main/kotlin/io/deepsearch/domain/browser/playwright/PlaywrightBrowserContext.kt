package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.BrowserContext
import io.deepsearch.domain.browser.IBrowserContext
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Playwright-backed browser context.
 * 
 * Uses the browser's mutex to ensure thread-safe access to the Playwright API.
 */
class PlaywrightBrowserContext(
    private val context: BrowserContext,
    private val apiMutex: Mutex
) : IBrowserContext {

    private val stealthScript: String by lazy {
        val stream = this::class.java.classLoader.getResourceAsStream("out/stealth.js")
            ?: throw IllegalStateException("Resource not found: out/stealth.js")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override suspend fun newPage(): IBrowserPage {
        val page = apiMutex.withLock { 
            val p = context.newPage()
            // Inject stealth script before any page navigation
            p.addInitScript(stealthScript)
            p
        }
        return PlaywrightBrowserPage(page, apiMutex)
    }
    
    override suspend fun close() {
        apiMutex.withLock { context.close() }
    }
}

