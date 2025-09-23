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
            val html = webpage.getFullHtml()
            val output = popupIdentificationAgent.generate(
                PopupIdentificationInput(
                    screenshotBytes = screenshot.bytes,
                    mimetype = screenshot.mimeType,
                    html = html
                )
            )

            val dismissXPath = output.dismissButtonXPath
            if (dismissXPath.isNullOrBlank()) {
                logger.debug("No popup dismiss button detected on iteration {}", iteration)
                return
            }

            logger.debug("Attempting to click dismiss button via XPath: {}", dismissXPath)
            webpage.clickByXPathSelector(dismissXPath)
        }
    }
}


