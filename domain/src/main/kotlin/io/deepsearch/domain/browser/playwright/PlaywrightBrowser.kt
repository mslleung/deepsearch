package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlaywrightBrowser() : IBrowser {
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(true)
    )
    private val apiMutex: Mutex = Mutex()

    override suspend fun createContext(): IBrowserContext {
        val context = apiMutex.withLock { browser.newContext() }
        return PlaywrightBrowserContext(context, apiMutex)
    }

    override suspend fun close() {
        try {
            apiMutex.withLock { browser.close() }
        } finally {
            apiMutex.withLock { playwright.close() }
        }
    }
}