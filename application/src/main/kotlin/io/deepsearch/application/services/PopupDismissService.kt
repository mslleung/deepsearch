package io.deepsearch.application.services

import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.agents.PopupIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IPopupDismissService {
    /**
     * Dismisses popups and cookie banners in a loop until none remain, or a safety cap is reached.
     */
    suspend fun dismissAll(webpage: IBrowserPage)
}

class PopupDismissService(
    private val popupIdentificationAgent: IPopupIdentificationAgent
) : IPopupDismissService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun dismissAll(webpage: IBrowserPage) {
        // Safety to avoid infinite loops due to mis-detected selectors
        repeat(5) { iteration ->
            val screenshot = webpage.takeScreenshot()
            val result = popupIdentificationAgent.generate(
                PopupIdentificationInput(screenshot.bytes, screenshot.mimeType)
            ).result

            if (result.exists == false) {
                logger.debug("No popup detected on iteration {}", iteration)
                return
            }

            val containerXPath = result.containerXPath
            if (containerXPath == null || containerXPath.isBlank()) {
                logger.debug("Popup detected but no container XPath provided on iteration {}", iteration)
                return
            }

            logger.debug("Attempting to remove popup container via XPath: {}", containerXPath)
            try {
                webpage.removeElement(containerXPath)
            } catch (t: Throwable) {
                logger.debug("Failed to remove popup container on iteration {}: {}", iteration, t.message)
            }
        }
    }
}


