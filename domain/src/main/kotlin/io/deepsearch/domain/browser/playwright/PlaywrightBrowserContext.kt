package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.BrowserContext
import io.deepsearch.domain.browser.IBrowserContext
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlaywrightBrowserContext(
    private val context: BrowserContext,
    private val apiMutex: Mutex
) : IBrowserContext {

    override suspend fun newPage(): IBrowserPage {
        val page = apiMutex.withLock { context.newPage() }
        return PlaywrightBrowserPage(page, apiMutex)
    }
    
    override suspend fun close() {
        apiMutex.withLock { context.close() }
    }
}