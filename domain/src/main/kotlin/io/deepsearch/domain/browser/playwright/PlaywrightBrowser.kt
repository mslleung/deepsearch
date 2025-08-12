package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.IBrowserContext

class PlaywrightBrowser : IBrowser {
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(true)
    )

    override fun createContext(): IBrowserContext {
        val context = browser.newContext()
        return PlaywrightBrowserContext(context)
    }

    override fun close() {
        try {
            browser.contexts().forEach { context ->
                context.pages().forEach { page ->
                    page.close()
                }
                context.close()
            }
            browser.close()
        } finally {
            playwright.close()
        }
    }
}