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
            return existing.popupXPaths.map { normalizeXPath(it) }
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

    /**
     * Normalizes XPath expressions to ensure they work correctly with Playwright.
     * 
     * Converts absolute paths like `/div[@id='x']` to relative paths `//div[@id='x']`
     * since `/div` expects div to be a direct child of the document root, which is invalid for HTML.
     * Preserves valid absolute paths like `/html/body/div[@id='x']`.
     */
    private fun normalizeXPath(xpath: String): String {
        val trimmed = xpath.trim()
        
        // If XPath starts with a single `/` followed by something other than `html`, convert to `//`
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.startsWith("/html")) {
            return "/$trimmed"
        }
        
        return trimmed
    }
}
