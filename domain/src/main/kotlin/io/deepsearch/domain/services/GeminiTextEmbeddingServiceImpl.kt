package io.deepsearch.domain.services

import io.deepsearch.domain.config.GeminiApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Gemini API implementation of text embedding service.
 * 
 * This implementation uses the Gemini API directly via HTTP requests,
 * suitable for development environments.
 */
class GeminiTextEmbeddingServiceImpl(
    private val geminiApiConfig: GeminiApiConfig
) : ITextEmbeddingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30000 // 30 seconds for embedding requests
        }
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class EmbedContentRequest(
        val model: String,
        val content: Content,
        val taskType: String? = null,
        val outputDimensionality: Int? = null
    )

    @Serializable
    data class Content(
        val parts: List<Part>
    )

    @Serializable
    data class Part(
        val text: String
    )

    @Serializable
    data class EmbedContentResponse(
        val embedding: Embedding
    )

    @Serializable
    data class Embedding(
        val values: List<Float>
    )

    @Serializable
    data class BatchEmbedContentsRequest(
        val requests: List<EmbedContentRequest>
    )

    @Serializable
    data class BatchEmbedContentsResponse(
        val embeddings: List<Embedding>
    )

    override suspend fun embedDocuments(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) {
            return emptyList()
        }

        logger.debug("Embedding {} documents with Gemini API", texts.size)

        try {
            // Create batch request for multiple documents
            val requests = texts.map { text ->
                EmbedContentRequest(
                    model = "models/gemini-embedding-001",
                    content = Content(parts = listOf(Part(text = text))),
                    taskType = "RETRIEVAL_DOCUMENT",
                    outputDimensionality = 1536
                )
            }

            val requestBody = BatchEmbedContentsRequest(requests = requests)
            val requestJson = json.encodeToString(BatchEmbedContentsRequest.serializer(), requestBody)

            val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents") {
                contentType(ContentType.Application.Json)
                headers {
                    append("x-goog-api-key", geminiApiConfig.apiKey)
                }
                setBody(requestJson)
            }

            if (response.status.value !in 200..299) {
                logger.error("Gemini API returned error: ${response.status.value}")
                val errorBody = response.bodyAsText()
                logger.error("Error response: $errorBody")
                throw RuntimeException("Failed to generate embeddings: HTTP ${response.status.value}")
            }

            val responseBody = response.bodyAsText()
            val batchResponse = json.decodeFromString<BatchEmbedContentsResponse>(responseBody)

            logger.debug("Successfully generated {} embeddings", batchResponse.embeddings.size)

            return batchResponse.embeddings.map { it.values }
        } catch (e: Exception) {
            logger.error("Error generating document embeddings: ${e.message}", e)
            throw e
        }
    }

    override suspend fun embedQuery(text: String): List<Float> {
        logger.debug("Embedding query with Gemini API")

        try {
            val requestBody = EmbedContentRequest(
                model = "models/gemini-embedding-001",
                content = Content(parts = listOf(Part(text = text))),
                taskType = "RETRIEVAL_QUERY",
                outputDimensionality = 1536
            )

            val requestJson = json.encodeToString(EmbedContentRequest.serializer(), requestBody)

            val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent") {
                contentType(ContentType.Application.Json)
                headers {
                    append("x-goog-api-key", geminiApiConfig.apiKey)
                }
                setBody(requestJson)
            }

            if (response.status.value !in 200..299) {
                logger.error("Gemini API returned error: ${response.status.value}")
                val errorBody = response.bodyAsText()
                logger.error("Error response: $errorBody")
                throw RuntimeException("Failed to generate query embedding: HTTP ${response.status.value}")
            }

            val responseBody = response.bodyAsText()
            val embedResponse = json.decodeFromString<EmbedContentResponse>(responseBody)

            logger.debug("Successfully generated query embedding with {} dimensions", embedResponse.embedding.values.size)

            return embedResponse.embedding.values
        } catch (e: Exception) {
            logger.error("Error generating query embedding: ${e.message}", e)
            throw e
        }
    }
}

