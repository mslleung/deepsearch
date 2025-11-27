package io.deepsearch.application.searchorchestrators.cacheonlysearch

import io.deepsearch.application.searchorchestrators.ISearchOrchestrator
import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.agents.AnswerSynthesisInput
import io.deepsearch.domain.agents.IAnswerSynthesisAgent
import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchMode
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface ICacheOnlySearchOrchestrator : ISearchOrchestrator

/**
 * Orchestrates cache-only search using pre-indexed data.
 * Performs hybrid search on cached webpages and generates answers without live crawling.
 * This is faster than live-crawling but limited to previously indexed content.
 */
@OptIn(ExperimentalTime::class)
class CacheOnlySearchOrchestrator(
    private val webpageCacheService: WebpageCacheService,
    private val streamingSourceShortlistAgent: IStreamingSourceShortlistAgent,
    private val answerSynthesisAgent: IAnswerSynthesisAgent,
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val dispatchers: IDispatcherProvider
) : ICacheOnlySearchOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
    ): QuerySessionId = withContext(dispatchers.io) {
        val sessionId: QuerySessionId
        val executionTime = measureTimeMillis {
            sessionId = executeSearchForQuery(searchQuery, maxCacheAge, apiKeyId)
        }

        logger.info(
            "Cache-only search completed in {} ms for query: {}",
            executionTime,
            searchQuery.query
        )
        sessionId
    }

    /**
     * Execute the cache-only search workflow.
     * 1. Create session
     * 2. Perform hybrid search on cached data
     * 3. Shortlist sources
     * 4. Synthesize answer
     * 5. Complete session
     */
    private suspend fun executeSearchForQuery(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        apiKeyId: ApiKeyId
    ): QuerySessionId {
        val session = querySessionService.createSession(searchQuery.query, searchQuery.url, apiKeyId, SearchMode.CACHE_ONLY)
        val sessionId = session.id

        try {
            logger.debug(
                "[{}] Executing cache-only search for query: {}",
                sessionId.value,
                searchQuery.query
            )

            // Step 1: Perform hybrid search on cached data
            val cachedWebpages = webpageCacheService.searchHybrid(
                query = searchQuery.query,
                baseUrl = searchQuery.url,
                maxCacheAge = maxCacheAge,
                limit = 20,
                sessionId = sessionId
            )

            logger.debug(
                "[{}] Hybrid search found {} cached webpages",
                sessionId.value,
                cachedWebpages.size
            )

            // Filter valid webpages with markdown content
            val validWebpages = cachedWebpages.filter { webpage ->
                !webpage.markdown.isNullOrBlank()
            }

            if (validWebpages.isEmpty()) {
                logger.info("[{}] No cached content found for query", sessionId.value)
                querySessionService.completeSessionLinksExhausted(
                    sessionId,
                    "No relevant webpages found in cache."
                )
                return sessionId
            }

            // Record URL accesses for cached pages
            validWebpages.forEach { webpage ->
                val cachedAccess = CachedUrlAccess(url = webpage.url, timestamp = Clock.System.now())
                urlAccessService.recordUrlAccess(sessionId, cachedAccess)
            }

            // Convert to MarkdownSource
            val markdownSources = validWebpages.map { webpage ->
                MarkdownSource(
                    url = webpage.url,
                    title = webpage.title,
                    description = webpage.description,
                    markdown = webpage.markdown!!
                )
            }

            // Step 2: Run through source shortlist agent
            val shortlistOutput = streamingSourceShortlistAgent.generate(
                StreamingSourceShortlistInput(
                    query = searchQuery.query,
                    currentShortlist = emptyList(),
                    newMarkdownBatch = markdownSources
                )
            )

            // Record token usage for shortlist agent
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "StreamingSourceShortlistAgent",
                modelName = shortlistOutput.tokenUsage.modelName,
                promptTokens = shortlistOutput.tokenUsage.promptTokens,
                outputTokens = shortlistOutput.tokenUsage.outputTokens,
                totalTokens = shortlistOutput.tokenUsage.totalTokens
            )

            logger.debug(
                "[{}] Shortlist created: {} sources",
                sessionId.value,
                shortlistOutput.updatedShortlist.size
            )

            // Step 3: Synthesize answer
            val synthesisOutput = answerSynthesisAgent.generate(
                AnswerSynthesisInput(
                    query = searchQuery.query,
                    shortlistedSources = shortlistOutput.updatedShortlist
                )
            )

            // Record token usage for synthesis agent
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "AnswerSynthesisAgent",
                modelName = synthesisOutput.tokenUsage.modelName,
                promptTokens = synthesisOutput.tokenUsage.promptTokens,
                outputTokens = synthesisOutput.tokenUsage.outputTokens,
                totalTokens = synthesisOutput.tokenUsage.totalTokens
            )

            // Mark answer sources as used in answer
            val answerSources = shortlistOutput.updatedShortlist.map { it.url }
            if (answerSources.isNotEmpty()) {
                urlAccessService.markUrlsAsUsedInAnswer(sessionId, answerSources)
            }

            // Step 4: Complete session
            querySessionService.completeSessionAnswerComplete(sessionId, synthesisOutput.answer)

            return sessionId
        } catch (e: Exception) {
            logger.error("[{}] Error in cache-only search: {}", sessionId.value, e.message, e)
            querySessionService.hardTimeout(sessionId, e.message ?: "Unknown error")
            throw e
        }
    }
}

