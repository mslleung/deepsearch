package io.deepsearch.domain.browser.playwright

import com.microsoft.playwright.Page
import io.deepsearch.domain.browser.IBrowserPage

class PlaywrightBrowserPage(
    private val page: Page
) : IBrowserPage {
    
    override fun navigate(url: String) {
        page.navigate(url)
    }
}