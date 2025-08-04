package models.valueobjects

import com.microsoft.playwright.BrowserContext
import models.valueobjects.IBrowserContext
import models.valueobjects.IBrowserPage

class PlaywrightBrowserContext(
    private val context: BrowserContext
) : IBrowserContext {
    
    override fun newPage(): IBrowserPage {
        val page = context.newPage()
        return PlaywrightBrowserPage(page)
    }
}