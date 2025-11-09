package io.deepsearch.domain.services

import io.deepsearch.domain.config.VertexAiConfig
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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Vertex AI implementation of text embedding service.
 * 
 * This implementation uses Vertex AI via HTTP requests with application default credentials,
 * suitable for production environments running on Google Cloud.
 */
class VertexAiTextEmbeddingServiceImpl(
    private val vertexAiConfig: VertexAiConfig
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
    data class Instance(
        val taskType: String,
        val content: String
    )

    @Serializable
    data class Parameters(
        val outputDimensionality: Int
    )

    @Serializable
    data class PredictRequest(
        val instances: List<Instance>,
        val parameters: Parameters
    )

    @Serializable
    data class PredictResponse(
        val predictions: List<Prediction>
    )

    @Serializable
    data class Prediction(
        val embeddings: EmbeddingValues
    )

    @Serializable
    data class EmbeddingValues(
        val values: List<Float>
    )

    /**
     * Get access token using gcloud auth print-access-token.
     * In production, this would use Application Default Credentials.
     */
    private fun getAccessToken(): String {
        try {
            val process = ProcessBuilder("gcloud", "auth", "print-access-token")
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("Failed to get access token: $output")
            }

            return output
        } catch (e: Exception) {
            logger.error("Failed to get access token", e)
            throw RuntimeException("Failed to get access token for Vertex AI", e)
        }
    }

    override suspend fun embedDocuments(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) {
            return emptyList()
        }

        logger.debug("Embedding {} documents with Vertex AI", texts.size)

        try {
            val instances = texts.map { text ->
                Instance(
                    taskType = "RETRIEVAL_DOCUMENT",
                    content = text
                )
            }

            val requestBody = PredictRequest(
                instances = instances,
                parameters = Parameters(outputDimensionality = 1536)
            )

            val requestJson = json.encodeToString(PredictRequest.serializer(), requestBody)
            val accessToken = getAccessToken()

            val url = "https://${vertexAiConfig.location}-aiplatform.googleapis.com/v1/projects/${vertexAiConfig.projectId}/locations/${vertexAiConfig.location}/publishers/google/models/gemini-embedding-001:predict"

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                headers {
                    append("Authorization", "Bearer $accessToken")
                }
                setBody(requestJson)
            }

            if (response.status.value !in 200..299) {
                logger.error("Vertex AI returned error: ${response.status.value}")
                val errorBody = response.bodyAsText()
                logger.error("Error response: $errorBody")
                throw RuntimeException("Failed to generate embeddings: HTTP ${response.status.value}")
            }

            val responseBody = response.bodyAsText()
            val predictResponse = json.decodeFromString<PredictResponse>(responseBody)

            logger.debug("Successfully generated {} embeddings", predictResponse.predictions.size)

            return predictResponse.predictions.map { it.embeddings.values }
        } catch (e: Exception) {
            logger.error("Error generating document embeddings: ${e.message}", e)
            throw e
        }
    }

    override suspend fun embedQuery(text: String): List<Float> {
        logger.debug("Embedding query with Vertex AI")

        try {
            val instances = listOf(
                Instance(
                    taskType = "RETRIEVAL_QUERY",
                    content = text
                )
            )

            val requestBody = PredictRequest(
                instances = instances,
                parameters = Parameters(outputDimensionality = 1536)
            )

            val requestJson = json.encodeToString(PredictRequest.serializer(), requestBody)
            val accessToken = getAccessToken()

            val url = "https://${vertexAiConfig.location}-aiplatform.googleapis.com/v1/projects/${vertexAiConfig.projectId}/locations/${vertexAiConfig.location}/publishers/google/models/gemini-embedding-001:predict"

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                headers {
                    append("Authorization", "Bearer $accessToken")
                }
                setBody(requestJson)
            }

            if (response.status.value !in 200..299) {
                logger.error("Vertex AI returned error: ${response.status.value}")
                val errorBody = response.bodyAsText()
                logger.error("Error response: $errorBody")
                throw RuntimeException("Failed to generate query embedding: HTTP ${response.status.value}")
            }

            val responseBody = response.bodyAsText()
            val predictResponse = json.decodeFromString<PredictResponse>(responseBody)

            logger.debug("Successfully generated query embedding with {} dimensions", predictResponse.predictions[0].embeddings.values.size)

            return predictResponse.predictions[0].embeddings.values
        } catch (e: Exception) {
            logger.error("Error generating query embedding: ${e.message}", e)
            throw e
        }
    }
}

