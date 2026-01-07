package io.deepsearch.application.services

import io.deepsearch.domain.agents.FullQueryBreakdownInput
import io.deepsearch.domain.agents.IFullQueryBreakdownAgent
import io.deepsearch.domain.agents.ISimpleQueryBreakdownAgent
import io.deepsearch.domain.agents.SimpleQueryBreakdownInput
import io.deepsearch.domain.models.valueobjects.CachedWebsiteContext
import io.deepsearch.domain.models.valueobjects.QueryProcessingResult
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.repositories.IWebsiteContextRepository
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service for processing queries with website context.
 * Runs in parallel with link discovery to avoid adding latency.
 */
interface IQueryProcessingService {
    /**
     * Process a query to generate an expanded query with fulfillment requirements.
     * Returns a Flow that emits exactly one QueryProcessingResult.
     * 
     * Logic:
     * 1. Check cache for WebsiteContext by URL
     * 2. If cache hit (and not expired): use SimpleQueryBreakdownAgent
     * 3. If cache miss: use FullQueryBreakdownAgent (uses Gemini URL Context tool) → cache context
     * 
     * @param searchQuery The original user query
     * @param maxCacheAge Maximum cache age in milliseconds (null means no expiry)
     * @param sessionId Session ID for token usage tracking
     * @return Flow that emits QueryProcessingResult
     */
    fun processQueryFlow(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        sessionId: QuerySessionId
    ): Flow<QueryProcessingResult>
}

@OptIn(ExperimentalTime::class)
class QueryProcessingService(
    private val websiteContextRepository: IWebsiteContextRepository,
    private val simpleQueryBreakdownAgent: ISimpleQueryBreakdownAgent,
    private val fullQueryBreakdownAgent: IFullQueryBreakdownAgent,
    private val normalizeUrlService: INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService
) : IQueryProcessingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun processQueryFlow(
        searchQuery: SearchQuery,
        maxCacheAge: Long?,
        sessionId: QuerySessionId
    ): Flow<QueryProcessingResult> = flow {
        val normalizedUrl = normalizeUrlService.normalize(searchQuery.url) ?: searchQuery.url

        logger.debug("[{}] Processing query with context for URL: {}", sessionId.value, normalizedUrl)

        // Check cache for website context
        val cachedContext = websiteContextRepository.findByUrl(normalizedUrl)

        val result = if (cachedContext != null && !isExpired(cachedContext, maxCacheAge)) {
            // Cache hit - use simple breakdown agent
            logger.debug("[{}] Website context cache HIT for {}", sessionId.value, normalizedUrl)
            processWithCachedContext(searchQuery, cachedContext.toWebsiteContext(), sessionId)
        } else {
            // Cache miss - use full breakdown agent with URL Context tool
            logger.debug("[{}] Website context cache MISS for {}", sessionId.value, normalizedUrl)
            processWithUrlContext(searchQuery, normalizedUrl, sessionId)
        }

        emit(result)
    }

    private fun isExpired(cached: CachedWebsiteContext, maxCacheAge: Long?): Boolean {
        if (maxCacheAge == null) return false
        val age = Clock.System.now() - cached.cachedAt
        return age.inWholeMilliseconds > maxCacheAge
    }

    private suspend fun processWithCachedContext(
        searchQuery: SearchQuery,
        context: io.deepsearch.domain.models.valueobjects.WebsiteContext,
        sessionId: QuerySessionId
    ): QueryProcessingResult {
        val output = simpleQueryBreakdownAgent.generate(
            SimpleQueryBreakdownInput(
                searchQuery = searchQuery,
                websiteContext = context
            )
        )

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "SimpleQueryBreakdownAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )

        logger.info(
            "[{}] Query processed with cached context: '{}' → '{}', followUpQueries={}",
            sessionId.value,
            searchQuery.query,
            output.expandedQuery,
            output.followUpQueries
        )

        return QueryProcessingResult(
            originalQuery = searchQuery.query,
            expandedQuery = output.expandedQuery,
            fulfillmentRequirements = output.fulfillmentRequirements,
            followUpQueries = output.followUpQueries,
            websiteContext = context
        )
    }

    /**
     * Process query using FullQueryBreakdownAgent which uses Gemini URL Context tool.
     * The agent fetches page content via URL Context - no manual HTTP fetch needed.
     */
    private suspend fun processWithUrlContext(
        searchQuery: SearchQuery,
        normalizedUrl: String,
        sessionId: QuerySessionId
    ): QueryProcessingResult {
        // Use full breakdown agent - it uses Gemini URL Context tool to fetch the page
        val output = fullQueryBreakdownAgent.generate(
            FullQueryBreakdownInput(
                searchQuery = searchQuery,
                url = normalizedUrl
            )
        )

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "FullQueryBreakdownAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )

        // Cache the extracted context
        websiteContextRepository.upsert(
            CachedWebsiteContext(
                url = normalizedUrl,
                title = output.websiteContext.title,
                description = output.websiteContext.description,
                contentSummary = output.websiteContext.contentSummary,
                cachedAt = Clock.System.now()
            )
        )

        logger.info(
            "[{}] Query processed with URL Context tool: '{}' → '{}', followUpQueries={} (context cached)",
            sessionId.value,
            searchQuery.query,
            output.expandedQuery,
            output.followUpQueries
        )

        return QueryProcessingResult(
            originalQuery = searchQuery.query,
            expandedQuery = output.expandedQuery,
            fulfillmentRequirements = output.fulfillmentRequirements,
            followUpQueries = output.followUpQueries,
            websiteContext = output.websiteContext
        )
    }
}
