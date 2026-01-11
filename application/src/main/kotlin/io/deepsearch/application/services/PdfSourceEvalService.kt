package io.deepsearch.application.services

import io.deepsearch.domain.agents.PdfSourceEvalInput
import io.deepsearch.domain.agents.PdfSourceEvalOutput
import io.deepsearch.domain.agents.IPdfSourceEvalAgent
import io.deepsearch.domain.models.entities.PdfSourceEvalCache
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.repositories.IPdfSourceEvalCacheRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Service for evaluating PDF sources with caching.
 * 
 * Wraps the PdfSourceEvalAgent with:
 * - Hash-based caching using SHA-256 of (query + extractedText)
 * - Token usage tracking
 * 
 * Cache hits return stored results with original token counts for accurate usage tracking.
 */
interface IPdfSourceEvalService {
    /**
     * Evaluate a PDF source for a search query.
     * 
     * @param input The PDF source and search query
     * @param sessionId Session ID for token usage tracking
     * @return Evaluated source with extracted facts, or null if not relevant
     */
    suspend fun evaluate(input: PdfSourceEvalInput, sessionId: SessionId): PdfSourceEvalOutput
}

@OptIn(ExperimentalTime::class)
class PdfSourceEvalService(
    private val pdfSourceEvalAgent: IPdfSourceEvalAgent,
    private val cacheRepository: IPdfSourceEvalCacheRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : IPdfSourceEvalService {

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

    override suspend fun evaluate(input: PdfSourceEvalInput, sessionId: SessionId): PdfSourceEvalOutput {
        val pdfSource = input.pdfSource
        // Use expandedQuery with site context from source URL for cache key
        val queryWithSite = "${input.expandedQuery} site:${extractDomain(pdfSource.url)}"
        
        // Compute content hash for caching (query + extractedText)
        val contentHash = computeContentHash(queryWithSite, pdfSource.extractedText)
        
        // Check cache first
        val cachedResult = cacheRepository.findByHash(contentHash)
        if (cachedResult != null) {
            logger.debug(
                "[PdfSourceEvalService] Cache HIT for {}, returning cached result",
                pdfSource.url
            )
            
            // No token usage recorded on cache hit - tokens were already counted
            // when the original evaluation was cached. Recording again would double-count.
            
            return deserializeCachedResult(cachedResult, pdfSource.url)
        }
        
        logger.debug("[PdfSourceEvalService] Cache MISS for {}, calling agent", pdfSource.url)
        
        // Call the agent
        val output = pdfSourceEvalAgent.generate(input)
        
        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "PdfSourceEvalAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )
        
        // Cache the result
        cacheResult(contentHash, output.evaluatedSource)
        
        return output
    }

    /**
     * Computes SHA-256 hash of the query + extractedText for cache key.
     */
    private fun computeContentHash(query: String, extractedText: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(query.toByteArray(Charsets.UTF_8))
        digest.update(extractedText.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /**
     * Caches the evaluation result.
     */
    private suspend fun cacheResult(
        contentHash: ByteArray,
        evaluatedSource: EvaluatedSource?
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

            val cache = PdfSourceEvalCache(
                contentHash = contentHash,
                evaluatedSourceJson = cachedJson,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                version = 0
            )
            cacheRepository.upsert(cache)
        } catch (e: Exception) {
            logger.warn("Failed to cache PDF source eval result: {}", e.message)
        }
    }

    /**
     * Deserializes a cached result back to PdfSourceEvalOutput.
     */
    private fun deserializeCachedResult(
        cached: PdfSourceEvalCache,
        url: String
    ): PdfSourceEvalOutput {
        val tokenUsage = TokenUsageMetrics(
            modelName = "cached",
            promptTokens = 0,
            outputTokens = 0,
            totalTokens = 0
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
                    relevantImageIds = emptyList(),
                    isPreview = true  // PDF preview sources are always preview
                )
            } catch (e: Exception) {
                logger.warn("Failed to deserialize cached result for {}: {}", url, e.message)
                null
            }
        }

        return PdfSourceEvalOutput(
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
