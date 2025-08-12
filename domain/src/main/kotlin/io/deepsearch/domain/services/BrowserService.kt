package io.deepsearch.domain.services

import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.browser.playwright.PlaywrightBrowser

interface IBrowserService {
    fun createBrowser(): IBrowser
}

/**
 * Provide lifecycle management of browser automation instances.
 *
 * Browser runtimes (e.g. Chrome, Firefox etc.) are expensive.
 * We may potentially run thousands of them. This service helps manage
 * their lifecycle.
 */
class BrowserService(): IBrowserService {

    override fun createBrowser(): IBrowser {
        return PlaywrightBrowser()
    }
}
