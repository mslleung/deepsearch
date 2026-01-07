package io.deepsearch.application.services

import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.HtmlSourceEvalOutput
import io.deepsearch.domain.agents.IHtmlSourceEvalAgent
import io.deepsearch.domain.models.entities.HtmlSourceEvalCache
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.repositories.IHtmlSourceEvalCacheRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Service for evaluating HTML sources with caching.
 * 
 * Wraps the HtmlSourceEvalAgent with:
 * - Hash-based caching using SHA-256 of (query + cleanedHtml)
 * - Token usage tracking
 * 
 * Cache hits return stored results with original token counts for accurate usage tracking.
 */
interface IHtmlSourceEvalService {
    /**
     * Evaluate an HTML source for a search query.
     * 
     * @param input The HTML source and search query
     * @param sessionId Session ID for token usage tracking
     * @return Evaluated source with extracted facts, or null if not relevant
     */
    suspend fun evaluate(input: HtmlSourceEvalInput, sessionId: SessionId): HtmlSourceEvalOutput
}

@OptIn(ExperimentalTime::class)
class HtmlSourceEvalService(
    private val htmlSourceEvalAgent: IHtmlSourceEvalAgent,
    private val cacheRepository: IHtmlSourceEvalCacheRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : IHtmlSourceEvalService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Serializable version of EvaluatedSource for caching.
     */
    @Serializable
    private data class CachedEvaluatedSource(
        val url: String,
        val title: String?,
        val description: String?,
        val relevantFacts: List<String>,
        val contentDate: String?,
        val intention: String
    )

    override suspend fun evaluate(input: HtmlSourceEvalInput, sessionId: SessionId): HtmlSourceEvalOutput {
        val htmlSource = input.htmlSource
        // Use expandedQuery with site context from source URL for cache key
        val queryWithSite = "${input.expandedQuery} site:${extractDomain(htmlSource.url)}"
        
        // Compute content hash for caching (query + cleanedHtml)
        val contentHash = computeContentHash(queryWithSite, htmlSource.cleanedHtml)
        
        // Check cache first
        val cachedResult = cacheRepository.findByHash(contentHash)
        if (cachedResult != null) {
            logger.debug(
                "[HtmlSourceEvalService] Cache HIT for {}, returning cached result (tokens: {})",
                htmlSource.url,
                cachedResult.totalTokens
            )
            
            // Record token usage even for cache hits (for accurate billing)
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "HtmlSourceEvalAgent",
                modelName = "cached",
                promptTokens = cachedResult.promptTokens,
                outputTokens = cachedResult.outputTokens,
                totalTokens = cachedResult.totalTokens
            )
            
            return deserializeCachedResult(cachedResult, htmlSource.url)
        }
        
        logger.debug("[HtmlSourceEvalService] Cache MISS for {}, calling agent", htmlSource.url)
        
        // Call the agent
        val output = htmlSourceEvalAgent.generate(input)
        
        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "HtmlSourceEvalAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )
        
        // Cache the result
        cacheResult(contentHash, output.evaluatedSource, output.tokenUsage)
        
        return output
    }

    /**
     * Computes SHA-256 hash of the query + cleanedHtml for cache key.
     */
    private fun computeContentHash(query: String, cleanedHtml: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(query.toByteArray(Charsets.UTF_8))
        digest.update(cleanedHtml.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /**
     * Caches the evaluation result.
     */
    private suspend fun cacheResult(
        contentHash: ByteArray,
        evaluatedSource: EvaluatedSource?,
        tokenUsage: TokenUsageMetrics
    ) {
        try {
            val cachedJson = evaluatedSource?.let { source ->
                val cached = CachedEvaluatedSource(
                    url = source.url,
                    title = source.title,
                    description = source.description,
                    relevantFacts = source.relevantFacts.map { it.fact },
                    contentDate = source.contentDate,
                    intention = source.intention
                )
                json.encodeToString(cached)
            }

            val cache = HtmlSourceEvalCache(
                contentHash = contentHash,
                evaluatedSourceJson = cachedJson,
                promptTokens = tokenUsage.promptTokens,
                outputTokens = tokenUsage.outputTokens,
                totalTokens = tokenUsage.totalTokens,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                version = 0
            )
            cacheRepository.upsert(cache)
        } catch (e: Exception) {
            logger.warn("Failed to cache HTML source eval result: {}", e.message)
        }
    }

    /**
     * Deserializes a cached result back to HtmlSourceEvalOutput.
     */
    private fun deserializeCachedResult(
        cached: HtmlSourceEvalCache,
        url: String
    ): HtmlSourceEvalOutput {
        val tokenUsage = TokenUsageMetrics(
            modelName = "cached",
            promptTokens = cached.promptTokens,
            outputTokens = cached.outputTokens,
            totalTokens = cached.totalTokens
        )

        val evaluatedSource = cached.evaluatedSourceJson?.let { jsonStr ->
            try {
                val cachedSource = json.decodeFromString<CachedEvaluatedSource>(jsonStr)
                EvaluatedSource(
                    url = cachedSource.url,
                    title = cachedSource.title,
                    description = cachedSource.description,
                    relevantFacts = cachedSource.relevantFacts.map { RelevantFact(fact = it) },
                    contentDate = cachedSource.contentDate,
                    intention = cachedSource.intention,
                    relevantImageIds = emptyList()
                )
            } catch (e: Exception) {
                logger.warn("Failed to deserialize cached result for {}: {}", url, e.message)
                null
            }
        }

        return HtmlSourceEvalOutput(
            evaluatedSource = evaluatedSource,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Extracts the domain from a URL string.
     */
    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }
}

