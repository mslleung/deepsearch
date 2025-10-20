package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowser
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.entities.QuerySessionState
import io.deepsearch.domain.models.entities.FinishReason
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchBudget
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.deepsearch.application.services.IUrlContentProcessingService.UrlProcessingResult

interface IRecursiveLinkTraversalService {
    /**
     * Process discovered links recursively in parallel waves (breadth-first).
     * Runs in background scope and emits results as they become available.
     * Checks session state before each wave and stops if answer is complete.
     * Uses normalized URLs for deduplication, but navigates to original URLs.
     * 
     * @param sessionId Query session ID for coordination
     * @param initialLinks Links to start processing from
     * @param processedNormalizedUrls Set of already-processed normalized URLs for deduplication
     * @param searchQuery The search query for link discovery
     * @param browser Browser instance to use
     * @return Flow of UrlProcessingResult as pages are processed
     */
    fun traverseLinksRecursively(
        sessionId: String,
        initialLinks: List<WebpageLink>,
        processedNormalizedUrls: Set<String>,
        searchQuery: SearchQuery,
        browser: IBrowser,
        budget: SearchBudget
    ): Flow<UrlProcessingResult>
}

class RecursiveLinkTraversalService(
    private val urlContentProcessingService: IUrlContentProcessingService,
    private val normalizeUrlService: INormalizeUrlService,
    private val querySessionService: IQuerySessionService,
    private val dispatchers: DispatcherProvider
) : IRecursiveLinkTraversalService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    // Background scope for continuing link processing after answer is complete
    // Uses SupervisorJob so failures in background processing don't cancel parent
    private val backgroundScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private fun normalize(url: String): String = normalizeUrlService.normalize(url) ?: url

    private fun filterAndDistinctLinks(
        links: List<WebpageLink>,
        visitedNormalizedUrls: Set<String>
    ): List<WebpageLink> {
        return links
            .distinctBy { normalize(it.url) }
            .filterNot { normalize(it.url) in visitedNormalizedUrls }
    }

    private suspend fun processLinksInParallel(
        links: List<WebpageLink>,
        searchQuery: SearchQuery,
        browser: IBrowser
    ): List<UrlProcessingResult> = withContext(dispatchers.io) {
        links.map { link ->
            async {
                urlContentProcessingService.processUrl(link.url, searchQuery.query, browser)
            }
        }.awaitAll()
    }

    private fun toNormalizedUrls(results: List<UrlProcessingResult>): List<String> =
        results.map { normalize(it.url) }

    private fun discoverNextWave(
        batchResults: List<UrlProcessingResult>,
        visitedNormalizedUrls: Set<String>
    ): List<WebpageLink> {
        val discovered = batchResults.flatMap { it.discoveredLinks }
        return filterAndDistinctLinks(discovered, visitedNormalizedUrls)
    }

    private fun logWaveProgress(
        sessionId: String,
        waveNumber: Int,
        sessionState: QuerySessionState,
        batchSize: Int
    ) {
        logger.debug(
            "[{}] Wave {}: Session state={}, processing {} links",
            sessionId,
            waveNumber,
            sessionState,
            batchSize
        )
        if (sessionState == QuerySessionState.TRAILING_LINK_TRAVERSAL) {
            logger.info("[{}] Wave {}: Answer complete, processing final wave then stopping", sessionId, waveNumber)
        }
    }

    override fun traverseLinksRecursively(
        sessionId: String,
        initialLinks: List<WebpageLink>,
        processedNormalizedUrls: Set<String>,
        searchQuery: SearchQuery,
        browser: IBrowser,
        budget: SearchBudget
    ): Flow<UrlProcessingResult> = channelFlow {
        // Launch traversal in background scope - continues even after answer is complete
        backgroundScope.launch {
            try {
                var visitedNormalizedUrls = processedNormalizedUrls
                var currentBatch = filterAndDistinctLinks(initialLinks, visitedNormalizedUrls)

                var waveNumber = 1
                while (currentBatch.isNotEmpty()) {
                    // Enforce search budget before each wave via domain check
                    val session = querySessionService.getSession(sessionId)
                    session.checkSearchBudget(budget)?.let { exceededReason ->
                        logger.info("[{}] Budget exceeded: {}", sessionId, exceededReason)
                        querySessionService.setFinishReason(sessionId, exceededReason)
                        querySessionService.transitionToTrailingTraversal(sessionId)
                    }

                    // Check session state before each wave
                    val sessionState = querySessionService.getState(sessionId)
                    logWaveProgress(sessionId, waveNumber, sessionState, currentBatch.size)

                    // Process all URLs in this batch in parallel
                    val batchResults = processLinksInParallel(currentBatch, searchQuery, browser)

                    // Track visited URLs using normalized form
                    val batchNormalizedUrls = toNormalizedUrls(batchResults)
                    visitedNormalizedUrls = visitedNormalizedUrls + batchNormalizedUrls

                    // Update session with traversed URLs
                    querySessionService.addTraversedUrls(sessionId, batchNormalizedUrls)

                    // Emit each result as it's processed
                    batchResults.forEach { result ->
                        send(result)
                    }

                    logger.debug(
                        "[{}] Wave {} complete: {} pages processed",
                        sessionId,
                        waveNumber,
                        batchResults.size
                    )

                    // If trailing traversal (answer complete or budget exceeded), stop after completing this wave
                    if (sessionState == QuerySessionState.TRAILING_LINK_TRAVERSAL) {
                        logger.info("[{}] Final wave complete, marking session as FINISHED", sessionId)
                        querySessionService.finish(sessionId)
                        break
                    }

                    // Collect new links for next wave
                    val newLinks = discoverNextWave(batchResults, visitedNormalizedUrls)

                    logger.debug(
                        "[{}] Wave {}: {} new links discovered",
                        sessionId,
                        waveNumber,
                        newLinks.size
                    )

                    currentBatch = newLinks
                    waveNumber++
                }

                // Links exhausted - check if we should mark as FINISHED
                val finalState = querySessionService.getState(sessionId)
                if (finalState == QuerySessionState.LINK_TRAVERSAL) {
                    // Links exhausted before answer complete
                    logger.info("[{}] Links exhausted before answer complete, marking as FINISHED", sessionId)
                    querySessionService.setFinishReason(sessionId, FinishReason.LINKS_EXHAUSTED)
                    querySessionService.finish(sessionId)
                }

                logger.info("[{}] Link traversal complete: {} total waves", sessionId, waveNumber - 1)
            } catch (e: Exception) {
                logger.error("[{}] Error during link traversal: {}", sessionId, e.message, e)
                querySessionService.fail(sessionId, e.message ?: "Unknown error during link traversal")
                close(e)
                // Continue emitting any results we have - don't rethrow, background processing is best-effort
            }
        }
    }
}


