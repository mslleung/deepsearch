package io.deepsearch.domain.browser

import io.deepsearch.domain.browser.playwright.PlaywrightBrowser

interface IBrowserFactory {
    fun createBrowser(): IBrowser
}

/**
 * Provide lifecycle management of browser automation instances.
 *
 * Browser runtimes (e.g. Chrome, Firefox etc.) are expensive.
 * We may potentially run thousands of them. This service helps manage
 * their lifecycle.
 */
class BrowserFactory : IBrowserFactory {

    override fun createBrowser(): IBrowser {
        return PlaywrightBrowser()
    }
}