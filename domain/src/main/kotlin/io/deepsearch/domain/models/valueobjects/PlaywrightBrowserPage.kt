package io.deepsearch.domain.models.valueobjects

import com.microsoft.playwright.Page

class PlaywrightBrowserPage(
    private val page: Page
) : IBrowserPage {
    
    override fun navigate(url: String) {
        page.navigate(url)
    }
}