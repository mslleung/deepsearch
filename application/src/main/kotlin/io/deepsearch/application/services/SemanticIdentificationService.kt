package io.deepsearch.application.services

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.entities.WebpageSemanticElement
import io.deepsearch.domain.models.valueobjects.SessionId
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
     * @param webpage The webpage to analyze
     * @param sessionId Session ID for token tracking
     * @param snapshot Pre-captured page snapshot containing HTML and bounding boxes
     * @return SemanticElements containing all identified semantic elements grouped by type
     */
    suspend fun identifySemanticElements(
        webpage: IBrowserPage,
        sessionId: SessionId,
        snapshot: IBrowserPage.PageSnapshot
    ): SemanticElements
}

class SemanticIdentificationService(
    private val semanticIdentificationAgent: ISemanticIdentificationAgent,
    private val webpageSemanticElementRepository: IWebpageNavigationElementRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : ISemanticIdentificationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun identifySemanticElements(
        webpage: IBrowserPage,
        sessionId: SessionId,
        snapshot: IBrowserPage.PageSnapshot
    ): SemanticElements {
        // Use html hash for caching
        val pageHash = MessageDigest.getInstance("SHA-256").digest(snapshot.html.toByteArray())

        val cached = webpageSemanticElementRepository.findByHash(pageHash)
        if (cached != null) {
            logger.debug("Using cached semantic elements")
            return cached.elements
        }

        val identificationResult = semanticIdentificationAgent.generate(
            SemanticIdentificationInput(
                webpage = webpage,
                snapshot = snapshot
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "SemanticIdentificationAgent",
            modelName = identificationResult.tokenUsage.modelName,
            promptTokens = identificationResult.tokenUsage.promptTokens,
            outputTokens = identificationResult.tokenUsage.outputTokens,
            totalTokens = identificationResult.tokenUsage.totalTokens
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

