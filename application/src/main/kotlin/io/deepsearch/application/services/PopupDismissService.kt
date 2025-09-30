package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL

interface IPopupDismissService {
    /**
     * Removes all identified popup containers from the webpage.
     * Uses a persistent store to remember known popups for similar page layouts.
     */
    suspend fun dismissAll(webpage: IBrowserPage, url: URL)
}

class PopupDismissService(
    private val popupContainerIdentificationService: IPopupContainerIdentificationService
) : IPopupDismissService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun dismissAll(webpage: IBrowserPage, url: URL) {
        val screenshot = webpage.takeScreenshot()
        val html = webpage.getFullHtml()
        
        val popupXPaths = popupContainerIdentificationService.identifyPopupContainers(screenshot, html)
        
        if (popupXPaths.isEmpty()) {
            logger.debug("No popup containers detected")
            return
        }

        logger.debug("Detected {} popup containers", popupXPaths.size)
        removePopupsByXPaths(webpage, popupXPaths)
    }

    private suspend fun removePopupsByXPaths(webpage: IBrowserPage, xpaths: List<String>) {
        xpaths.forEach { xpath ->
            try {
                logger.debug("Removing popup container via XPath: {}", xpath)
                webpage.removeElement(xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove popup container with XPath '{}': {}", xpath, e.message)
            }
        }
    }
}


