package io.deepsearch.domain.models.valueobjects

import com.microsoft.playwright.BrowserContext

class PlaywrightBrowserContext(
    private val context: BrowserContext
) : IBrowserContext {
    
    override fun newPage(): IBrowserPage {
        val page = context.newPage()
        return PlaywrightBrowserPage(page)
    }
}