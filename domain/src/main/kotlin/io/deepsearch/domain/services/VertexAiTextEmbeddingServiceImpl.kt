package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.EmbedContentConfig
import io.deepsearch.domain.config.VertexAiConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Vertex AI implementation of text embedding service.
 * 
 * This implementation uses the Google GenAI SDK with Vertex AI configuration,
 * suitable for production environments running on Google Cloud.
 * Uses Application Default Credentials (ADC) for authentication.
 */
class VertexAiTextEmbeddingServiceImpl(
    private val vertexAiConfig: VertexAiConfig
) : ITextEmbeddingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    private val client: Client by lazy {
        // For Vertex AI, use default credentials and specify project/location
        Client.builder()
            .build()
    }

    override suspend fun embedDocuments(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) {
            return emptyList()
        }

        logger.debug("Embedding {} documents with Vertex AI", texts.size)

        return try {
            // Process each document individually (not using batch API)
            coroutineScope {
                texts.map { text ->
                    async {
                        embedSingleText(
                            text = text,
                            taskType = "RETRIEVAL_DOCUMENT"
                        )
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            logger.error("Error generating document embeddings: ${e.message}", e)
            throw e
        }
    }

    override suspend fun embedQuery(text: String): List<Float> {
        logger.debug("Embedding query with Vertex AI")

        return try {
            embedSingleText(
                text = text,
                taskType = "RETRIEVAL_QUERY"
            )
        } catch (e: Exception) {
            logger.error("Error generating query embedding: ${e.message}", e)
            throw e
        }
    }

    /**
     * Embed a single text using Vertex AI.
     */
    private suspend fun embedSingleText(text: String, taskType: String): List<Float> {
        // Use the SDK's REST embedContent method with proper configuration
        val config = EmbedContentConfig.builder()
            .taskType(taskType)
            .outputDimensionality(1536)
            .build()

        val response = client.models.embedContent("gemini-embedding-001", text, config)

        logger.debug("Successfully generated embedding for task type {}", taskType)

        // Extract the embedding values from the response  
        // The response contains embeddings as an Optional wrapping a list
        val embeddingsList = response.embeddings().orElseThrow { 
            RuntimeException("No embedding returned from API") 
        }
        if (embeddingsList.isEmpty()) {
            throw RuntimeException("No embedding returned from API")
        }
        return embeddingsList[0].values().orElseThrow {
            RuntimeException("No values in embedding")
        }
    }
}

