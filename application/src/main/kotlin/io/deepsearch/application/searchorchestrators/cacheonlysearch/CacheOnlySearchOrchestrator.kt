package io.deepsearch.application.searchorchestrators.cacheonlysearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.agents.AnswerSynthesisInput
import io.deepsearch.domain.agents.IAnswerSynthesisAgent
import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface ICacheOnlySearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates cache-only search using pre-indexed data.
 * Returns a Flow<SearchEvent> that emits session start and completion events.
 */
@OptIn(ExperimentalTime::class)
class CacheOnlySearchOrchestrator(
    private val webpageCacheService: WebpageCacheService,
    private val streamingSourceShortlistAgent: IStreamingSourceShortlistAgent,
    private val answerSynthesisAgent: IAnswerSynthesisAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val tokenUsageService: ILlmTokenUsageService
) : ICacheOnlySearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
    ): Flow<SearchEvent> = flow {
        val session = querySessionService.createSession(
            searchQuery.query,
            searchQuery.url,
            apiKeyId,
            SearchMode.CACHE_ONLY
        )
        val sessionId = session.id

        // Emit session created
        emit(
            SearchEvent.SessionCreated(
                sessionId = sessionId.value,
                query = searchQuery.query,
                url = searchQuery.url,
                mode = "cache-only"
            )
        )

        try {
            logger.debug("[{}] Executing cache-only search for query: {}", sessionId.value, searchQuery.query)

            // Step 1: Perform hybrid search on cached data
            val cachedWebpages = webpageCacheService.searchHybrid(
                query = searchQuery.query,
                baseUrl = searchQuery.url,
                maxCacheAge = maxCacheAge,
                limit = 20,
                sessionId = sessionId
            )

            logger.debug("[{}] Hybrid search found {} cached webpages", sessionId.value, cachedWebpages.size)

            // Filter valid webpages with markdown content
            val validWebpages = cachedWebpages.filter { !it.markdown.isNullOrBlank() }

            if (validWebpages.isEmpty()) {
                logger.info("[{}] No cached content found for query", sessionId.value)
                querySessionService.completeSessionLinksExhausted(sessionId, "No relevant webpages found in cache.")

                val completedSession = querySessionService.getSession(sessionId)
                emit(
                    SearchEvent.SessionCompleted(
                        sessionId = sessionId.value,
                        answer = "No relevant webpages found in cache.",
                        finishReason = "LINKS_EXHAUSTED",
                        durationMs = completedSession.durationMs,
                        answerSourceCount = 0
                    )
                )
                return@flow
            }

            // Record URL accesses and emit URL processed events
            validWebpages.forEach { webpage ->
                urlAccessService.recordUrlAccess(sessionId, CachedUrlAccess(webpage.url, Clock.System.now()))
                emit(
                    SearchEvent.UrlProcessed(
                        sessionId = sessionId.value,
                        url = webpage.url,
                        accessType = "CACHED",
                        title = webpage.title,
                        description = webpage.description,
                        markdownLength = webpage.markdown?.length
                    )
                )
            }

            // Convert to MarkdownSource
            val markdownSources = validWebpages.map {
                MarkdownSource(it.url, it.title, it.description, it.markdown!!)
            }

            // Step 2: Run through source shortlist agent
            val shortlistOutput = streamingSourceShortlistAgent.generate(
                StreamingSourceShortlistInput(searchQuery.query, emptyList(), markdownSources)
            )

            tokenUsageService.recordTokenUsage(
                sessionId, "StreamingSourceShortlistAgent",
                shortlistOutput.tokenUsage.modelName,
                shortlistOutput.tokenUsage.promptTokens,
                shortlistOutput.tokenUsage.outputTokens,
                shortlistOutput.tokenUsage.totalTokens
            )

            emit(
                SearchEvent.ShortlistUpdated(
                    sessionId = sessionId.value,
                    processedUrlCount = markdownSources.size,
                    shortlistedCount = shortlistOutput.updatedShortlist.size,
                    isGoodEnough = shortlistOutput.isGoodEnough,
                    reason = shortlistOutput.reason
                )
            )

            // Step 3: Synthesize answer
            val synthesisOutput = answerSynthesisAgent.generate(
                AnswerSynthesisInput(searchQuery.query, shortlistOutput.updatedShortlist)
            )

            tokenUsageService.recordTokenUsage(
                sessionId, "AnswerSynthesisAgent",
                synthesisOutput.tokenUsage.modelName,
                synthesisOutput.tokenUsage.promptTokens,
                synthesisOutput.tokenUsage.outputTokens,
                synthesisOutput.tokenUsage.totalTokens
            )

            // Mark answer sources
            val answerSources = shortlistOutput.updatedShortlist.map { it.url }
            if (answerSources.isNotEmpty()) {
                urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
            }

            // Step 4: Complete session
            querySessionService.completeSessionAnswerComplete(sessionId, synthesisOutput.answer)

            val completedSession = querySessionService.getSession(sessionId)
            emit(
                SearchEvent.SessionCompleted(
                    sessionId = sessionId.value,
                    answer = synthesisOutput.answer,
                    finishReason = "ANSWER_COMPLETE",
                    durationMs = completedSession.durationMs,
                    answerSourceCount = answerSources.size
                )
            )

        } catch (e: Exception) {
            logger.error("[{}] Error in cache-only search: {}", sessionId.value, e.message, e)
            querySessionService.hardTimeout(sessionId, e.message ?: "Unknown error")
            emit(
                SearchEvent.SessionError(
                    sessionId = sessionId.value,
                    errorType = e::class.simpleName ?: "Unknown",
                    errorMessage = e.message ?: "Unknown error"
                )
            )
        }
    }
}
