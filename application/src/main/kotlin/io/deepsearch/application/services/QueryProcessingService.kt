package io.deepsearch.application.services

import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.IUrlContextExtractionAgent
import io.deepsearch.domain.agents.QueryBreakdownInput
import io.deepsearch.domain.agents.UrlContextExtractionInput
import io.deepsearch.domain.models.valueobjects.CachedWebsiteContext
import io.deepsearch.domain.models.valueobjects.QueryProcessingResult
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebsiteContext
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
     * 2. If cache hit (and not expired): use QueryBreakdownAgent directly
     * 3. If cache miss: use UrlContextExtractionAgent first, then QueryBreakdownAgent → cache context
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
    private val queryBreakdownAgent: IQueryBreakdownAgent,
    private val urlContextExtractionAgent: IUrlContextExtractionAgent,
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
            // Cache hit - use query breakdown agent directly
            logger.debug("[{}] Website context cache HIT for {}", sessionId.value, normalizedUrl)
            processWithCachedContext(searchQuery, cachedContext.toWebsiteContext(), sessionId)
        } else {
            // Cache miss - extract context first, then break down query
            logger.debug("[{}] Website context cache MISS for {}", sessionId.value, normalizedUrl)
            processWithUrlContextExtraction(searchQuery, normalizedUrl, sessionId)
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
        context: WebsiteContext,
        sessionId: QuerySessionId
    ): QueryProcessingResult {
        val output = queryBreakdownAgent.generate(
            QueryBreakdownInput(
                searchQuery = searchQuery,
                websiteContext = context
            )
        )

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "QueryBreakdownAgent",
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
     * Process query by first extracting website context using URL Context tool,
     * then using QueryBreakdownAgent with the extracted context.
     */
    private suspend fun processWithUrlContextExtraction(
        searchQuery: SearchQuery,
        normalizedUrl: String,
        sessionId: QuerySessionId
    ): QueryProcessingResult {
        // Step 1: Extract website context using URL Context tool
        val extractionOutput = urlContextExtractionAgent.generate(
            UrlContextExtractionInput(url = normalizedUrl)
        )

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "UrlContextExtractionAgent",
            modelName = extractionOutput.tokenUsage.modelName,
            promptTokens = extractionOutput.tokenUsage.promptTokens,
            outputTokens = extractionOutput.tokenUsage.outputTokens,
            totalTokens = extractionOutput.tokenUsage.totalTokens
        )

        val websiteContext = extractionOutput.websiteContext

        // Cache the extracted context
        websiteContextRepository.upsert(
            CachedWebsiteContext(
                url = normalizedUrl,
                contentSummary = websiteContext.contentSummary,
                cachedAt = Clock.System.now()
            )
        )

        // Step 2: Break down query with extracted context
        val breakdownOutput = queryBreakdownAgent.generate(
            QueryBreakdownInput(
                searchQuery = searchQuery,
                websiteContext = websiteContext
            )
        )

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "QueryBreakdownAgent",
            modelName = breakdownOutput.tokenUsage.modelName,
            promptTokens = breakdownOutput.tokenUsage.promptTokens,
            outputTokens = breakdownOutput.tokenUsage.outputTokens,
            totalTokens = breakdownOutput.tokenUsage.totalTokens
        )

        logger.info(
            "[{}] Query processed with URL context extraction: '{}' → '{}', followUpQueries={} (context cached)",
            sessionId.value,
            searchQuery.query,
            breakdownOutput.expandedQuery,
            breakdownOutput.followUpQueries
        )

        return QueryProcessingResult(
            originalQuery = searchQuery.query,
            expandedQuery = breakdownOutput.expandedQuery,
            fulfillmentRequirements = breakdownOutput.fulfillmentRequirements,
            followUpQueries = breakdownOutput.followUpQueries,
            websiteContext = websiteContext
        )
    }
}
