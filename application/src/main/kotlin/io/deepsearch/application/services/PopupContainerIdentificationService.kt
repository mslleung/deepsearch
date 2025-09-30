package io.deepsearch.application.services

import io.deepsearch.domain.agents.IPopupContainerIdentificationAgent
import io.deepsearch.domain.agents.PopupContainerIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpagePopup
import io.deepsearch.domain.repositories.IWebpagePopupRepository
import java.security.MessageDigest

interface IPopupContainerIdentificationService {
    /**
     * Identifies popup containers on a webpage and returns their XPaths.
     * Uses a hash-based cache to avoid redundant LLM calls for similar page layouts.
     */
    suspend fun identifyPopupContainers(screenshot: IBrowserPage.Screenshot, html: String): List<String>
}

class PopupContainerIdentificationService(
    private val popupContainerIdentificationAgent: IPopupContainerIdentificationAgent,
    private val webpagePopupRepository: IWebpagePopupRepository
) : IPopupContainerIdentificationService {

    override suspend fun identifyPopupContainers(screenshot: IBrowserPage.Screenshot, html: String): List<String> {
        val pageHash = MessageDigest.getInstance("SHA-256").digest(screenshot.bytes)

        val existing = webpagePopupRepository.findByHash(pageHash)
        if (existing != null) {
            return existing.popupXPaths
        }

        val identificationResult = popupContainerIdentificationAgent.generate(
            PopupContainerIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = screenshot.mimeType,
                html = html
            )
        )

        val popupXPaths = identificationResult.popupContainerXPaths

        webpagePopupRepository.upsert(
            WebpagePopup(
                pageHash = pageHash,
                popupXPaths = popupXPaths
            )
        )

        return popupXPaths
    }
}
