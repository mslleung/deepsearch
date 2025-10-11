package io.deepsearch.application.services

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageNavigationElement
import io.deepsearch.domain.models.valueobjects.SemanticElement
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest

interface ISemanticIdentificationService {
    /**
     * Identifies all semantic elements (navigation elements + popups) on a webpage.
     * Uses a hash-based cache to avoid redundant LLM calls for similar page layouts.
     * 
     * @return SemanticElements containing all identified semantic elements grouped by type
     */
    suspend fun identifySemanticElements(webpage: IBrowserPage): List<SemanticElement>
}

class SemanticIdentificationService(
    private val semanticIdentificationAgent: ISemanticIdentificationAgent,
    private val webpageNavigationElementRepository: IWebpageNavigationElementRepository
) : ISemanticIdentificationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun identifySemanticElements(webpage: IBrowserPage): List<SemanticElement> {
        val screenshot = webpage.takeScreenshot()
        val html = webpage.getFullHtml()

        // Use screenshot hash for caching (more reliable than HTML for popup detection)
        val pageHash = MessageDigest.getInstance("SHA-256").digest(screenshot.bytes)

        val cached = webpageNavigationElementRepository.findByHash(pageHash)
        if (cached != null) {
            logger.debug("Using cached semantic elements")
            return cached.elements
        }

        val identificationResult = semanticIdentificationAgent.generate(
            SemanticIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = screenshot.mimeType,
                html = html
            )
        )

        val elements = identificationResult.elements.map {
            SemanticElement(xpath = it.xpath, type = it.type, note = it.note)
        }

        cacheSemanticElements(pageHash, elements)
        return elements
    }

    private suspend fun cacheSemanticElements(
        pageHash: ByteArray,
        elements: List<SemanticElement>
    ) {
        webpageNavigationElementRepository.upsert(
            WebpageNavigationElement(
                pageHash = pageHash,
                elements = elements
            )
        )
    }
}

