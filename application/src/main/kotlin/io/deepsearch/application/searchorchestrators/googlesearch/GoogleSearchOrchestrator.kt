package io.deepsearch.application.searchorchestrators.googlesearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.agents.GoogleTextSearchInput
import io.deepsearch.domain.agents.GoogleUrlContextSearchInput
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IGoogleSearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates Google search + URL Context, powered by Google Gemini.
 * Returns a Flow<SearchEvent> that emits session start and completion events.
 */
class GoogleSearchOrchestrator(
    private val googleTextSearchAgent: IGoogleTextSearchAgent,
    private val googleUrlContextSearchAgent: IGoogleUrlContextSearchAgent,
    private val querySessionService: io.deepsearch.application.services.IQuerySessionService,
    private val tokenUsageService: io.deepsearch.application.services.ILlmTokenUsageService
) : IGoogleSearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
    ): Flow<SearchEvent> = flow {
        logger.debug("GoogleSearchOrchestrator.execute start: '{}' on {}", searchQuery.query, searchQuery.url)

        // Create query session
        val session = querySessionService.createSession(
            searchQuery.query,
            searchQuery.url,
            apiKeyId,
            SearchMode.LIVE_CRAWLING
        )
        val sessionId = session.id

        // Emit session created
        emit(
            SearchEvent.SessionCreated(
                sessionId = sessionId,
                query = searchQuery.query,
                url = searchQuery.url,
                mode = "google-search"
            )
        )

        try {
            // 1) Run text search to discover candidate sources
            val googleTextSearchOutput = googleTextSearchAgent.generate(GoogleTextSearchInput(searchQuery))

            tokenUsageService.recordTokenUsage(
                sessionId, "GoogleTextSearchAgent",
                googleTextSearchOutput.tokenUsage.modelName,
                googleTextSearchOutput.tokenUsage.promptTokens,
                googleTextSearchOutput.tokenUsage.outputTokens,
                googleTextSearchOutput.tokenUsage.totalTokens
            )

            val baseUrl = searchQuery.url
            val candidateSources = googleTextSearchOutput.answerSources.filter { it.startsWith(baseUrl) }

            // 2) Select up to 20 matching sources, or fall back to base URL
            val selectedUrls = if (candidateSources.isNotEmpty()) candidateSources.take(20) else listOf(baseUrl)

            // Emit URL processed events
            selectedUrls.forEach { url ->
                emit(
                    SearchEvent.UrlProcessed(
                        sessionId = sessionId,
                        url = url,
                        accessType = "UNCACHED"
                    )
                )
            }

            // 3) Run URL-context agent
            val urlContextOutput = googleUrlContextSearchAgent.generate(
                GoogleUrlContextSearchInput(searchQuery.query, selectedUrls)
            )

            tokenUsageService.recordTokenUsage(
                sessionId, "GoogleUrlContextSearchAgent",
                urlContextOutput.tokenUsage.modelName,
                urlContextOutput.tokenUsage.promptTokens,
                urlContextOutput.tokenUsage.outputTokens,
                urlContextOutput.tokenUsage.totalTokens
            )

            // 4) Complete session (Google URL context search always produces an answer)
            querySessionService.completeSessionAnswerComplete(sessionId, urlContextOutput.content, answerFound = true)

            val sessionDetail = querySessionService.getSessionDetailInternal(sessionId)
            emit(
                SearchEvent.SessionCompleted(
                    sessionId = sessionId,
                    finishReason = "ANSWER_COMPLETE",
                    sessionDetail = sessionDetail
                )
            )

        } catch (e: Exception) {
            logger.error("[{}] Error in Google search: {}", sessionId.value, e.message, e)
            querySessionService.hardTimeout(sessionId, e.message ?: "Unknown error")
            emit(
                SearchEvent.SessionError(
                    sessionId = sessionId,
                    errorType = e::class.simpleName ?: "Unknown",
                    errorMessage = e.message ?: "Unknown error"
                )
            )
        }
    }
}
