package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.EmbedContentConfig
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.sqrt

/**
 * Gemini API implementation of text embedding service.
 * 
 * This implementation uses the Google GenAI SDK Client to call the Gemini API,
 * suitable for development environments.
 */
class GeminiTextEmbeddingServiceImpl(
    private val client: Client
) : ITextEmbeddingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val modelName = "gemini-embedding-001"

    override suspend fun embedDocuments(texts: List<String>): EmbeddingResult {
        if (texts.isEmpty()) {
            return EmbeddingResult(
                embeddings = emptyList(),
                tokenUsage = TokenUsageMetrics.empty(modelName)
            )
        }

        logger.debug("Embedding {} documents with Gemini API", texts.size)

        return try {
            var totalTokens = 0
            
            // Process each document individually (not using batch API)
            val embeddings = coroutineScope {
                texts.map { text ->
                    async {
                        val (embedding, tokens) = embedSingleText(
                            text = text,
                            taskType = "RETRIEVAL_DOCUMENT"
                        )
                        totalTokens += tokens
                        embedding
                    }
                }.awaitAll()
            }
            
            EmbeddingResult(
                embeddings = embeddings,
                tokenUsage = TokenUsageMetrics(
                    modelName = modelName,
                    promptTokens = totalTokens,
                    outputTokens = 0, // Embeddings don't have output tokens
                    totalTokens = totalTokens
                )
            )
        } catch (e: Exception) {
            logger.error("Error generating document embeddings: ${e.message}", e)
            throw e
        }
    }

    override suspend fun embedQuery(text: String): QueryEmbeddingResult {
        logger.debug("Embedding query with Gemini API")

        return try {
            val (embedding, tokenCount) = embedSingleText(
                text = text,
                taskType = "RETRIEVAL_QUERY"
            )
            
            QueryEmbeddingResult(
                embedding = embedding,
                tokenUsage = TokenUsageMetrics(
                    modelName = modelName,
                    promptTokens = tokenCount,
                    outputTokens = 0, // Embeddings don't have output tokens
                    totalTokens = tokenCount
                )
            )
        } catch (e: Exception) {
            logger.error("Error generating query embedding: ${e.message}", e)
            throw e
        }
    }

    /**
     * Embed a single text using the Gemini API via REST.
     * Returns pair of (embedding, tokenCount)
     */
    private suspend fun embedSingleText(text: String, taskType: String): Pair<List<Float>, Int> {
        // Use the SDK's REST embedContent method with proper configuration
        val config = EmbedContentConfig.builder()
            .taskType(taskType)
            .outputDimensionality(1536)
            .build()

        val response = withRateLimitRetry(this::class.simpleName!!) {
            client.models.embedContent(modelName, text, config)
        }

        // Estimate token count (embedding responses may not have full usageMetadata)
        // The token count is typically just the input tokens
        val tokenCount = text.split("\\s+".toRegex()).size // Rough estimate

        logger.debug("Successfully generated embedding for task type {}", taskType)

        // Extract the embedding values from the response
        // The response contains embeddings as an Optional wrapping a list
        val embeddingsList = response.embeddings().orElseThrow { 
            RuntimeException("No embedding returned from API") 
        }
        if (embeddingsList.isEmpty()) {
            throw RuntimeException("No embedding returned from API")
        }
        val embedding = embeddingsList[0].values().orElseThrow {
            RuntimeException("No values in embedding")
        }

        if (embedding.size != 1536) {
            throw RuntimeException("Expect 1536 dimensions but got ${embedding.size}")
        }
        
        // Normalize the embedding for dimensions other than 3072
        // See: https://ai.google.dev/gemini-api/docs/embeddings#quality-for-smaller-dimensions
        return Pair(normalizeEmbedding(embedding), tokenCount)
    }

    /**
     * Normalize an embedding vector to unit length.
     * Required for outputDimensionality < 3072 to ensure accurate semantic similarity.
     * Normalized embeddings allow cosine similarity to focus on direction rather than magnitude.
     */
    private fun normalizeEmbedding(embedding: List<Float>): List<Float> {
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0) {
            embedding.map { it / norm }
        } else {
            embedding
        }
    }
}

