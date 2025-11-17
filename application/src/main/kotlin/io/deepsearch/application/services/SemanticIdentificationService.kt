package io.deepsearch.application.services

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.entities.WebpageSemanticElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.repositories.IWebpageNavigationElementRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

interface ISemanticIdentificationService {
    /**
     * Identifies all semantic elements (navigation elements + popups) on a webpage.
     * Uses a hash-based cache to avoid redundant LLM calls for similar page layouts.
     *
     * @param html The full HTML of the webpage
     * @return SemanticElements containing all identified semantic elements grouped by type
     */
    suspend fun identifySemanticElements(
        html: String
    ): SemanticElements
}

class SemanticIdentificationService(
    private val semanticIdentificationAgent: ISemanticIdentificationAgent,
    private val webpageSemanticElementRepository: IWebpageNavigationElementRepository
) : ISemanticIdentificationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun identifySemanticElements(
        html: String
    ): SemanticElements {
        // Use html hash for caching
        val pageHash = MessageDigest.getInstance("SHA-256").digest(html.toByteArray())

        val cached = webpageSemanticElementRepository.findByHash(pageHash)
        if (cached != null) {
            logger.debug("Using cached semantic elements")
            return cached.elements
        }

        val identificationResult = semanticIdentificationAgent.generate(
            SemanticIdentificationInput(
                html = html
            )
        )

        val elements = identificationResult.elements

        cacheSemanticElements(pageHash, elements)
        return elements
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun cacheSemanticElements(
        pageHash: ByteArray,
        elements: SemanticElements
    ) {
        webpageSemanticElementRepository.upsert(
            WebpageSemanticElement(
                pageHash = pageHash,
                elements = elements
            )
        )
    }
}

