package services

import models.valueobjects.IBrowser
import models.valueobjects.PlaywrightBrowser

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
