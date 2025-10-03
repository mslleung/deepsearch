package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IPopupDismissService {
    /**
     * Removes all identified popup containers from the webpage.
     * Uses a persistent store to remember known popups for similar page layouts.
     */
    suspend fun dismissAll(webpage: IBrowserPage)
}

class PopupDismissService(
    private val popupContainerIdentificationService: IPopupContainerIdentificationService
) : IPopupDismissService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun dismissAll(webpage: IBrowserPage) {
        val screenshot = webpage.takeScreenshot()
        val html = webpage.getFullHtml()
        
        val popupXPaths = popupContainerIdentificationService.identifyPopupContainers(screenshot, html)
        
        if (popupXPaths.isEmpty()) {
            logger.debug("No popup containers detected")
            return
        }

        logger.debug("Detected {} popup containers", popupXPaths.size)
        popupXPaths.forEach { xpath ->
            logger.debug("Removing popup container via XPath: {}", xpath)
            webpage.removeElement(xpath)
        }
    }
}


