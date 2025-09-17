package io.deepsearch.application.services

import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.agents.PopupIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IPopupDismissService {
    /**
     * Dismisses popups and cookie banners in a loop until none remain, or a safety cap is reached.
     */
    suspend fun dismissAll(webpage: IBrowserPage)
}

class PopupDismissService(
    private val popupIdentificationAgent: IPopupIdentificationAgent,
) : IPopupDismissService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun dismissAll(webpage: IBrowserPage) {
        // Safety to avoid infinite loops due to mis-detected selectors
        repeat(5) { iteration ->
            val screenshot = webpage.takeScreenshot()
            val result = popupIdentificationAgent.generate(
                PopupIdentificationInput(screenshot.bytes, screenshot.mimeType)
            ).result

            if (!result.exists) {
                logger.debug("No popup detected on iteration {}", iteration)
                return
            }

            val selector = result.dismissSelector
            if (selector.isNullOrBlank()) {
                logger.debug("Popup detected but no dismiss selector provided on iteration {}", iteration)
                return
            }

            logger.debug("Attempting to dismiss popup via selector: {}", selector)
            val clicked = runCatching { webpage.clickByCssSelector(selector) }.getOrElse { false }
            if (!clicked) {
                logger.debug("Selector not found/clicked; stopping dismiss loop on iteration {}", iteration)
                return
            }
        }
    }
}


