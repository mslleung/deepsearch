package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.BrowserContext
import io.deepsearch.domain.browser.IBrowserContext
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.agents.ITableIdentificationAgent

class PlaywrightBrowserContext(
    private val context: BrowserContext,
    private val tableIdentificationAgent: ITableIdentificationAgent
) : IBrowserContext {
    
    override fun newPage(): IBrowserPage {
        val page = context.newPage()
        return PlaywrightBrowserPage(page, tableIdentificationAgent)
    }
}