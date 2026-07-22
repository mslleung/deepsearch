package io.deepsearch.domain.services

import com.google.genai.types.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Instant
import kotlin.time.ExperimentalTime

/**
 * Service interface for Gemini Batch API operations.
 * 
 * The Gemini Batch API provides 50% cost savings for large-scale processing,
 * with a target turnaround time of 24 hours (often faster).
 * 
 * Batch jobs support both content generation and embeddings.
 */
interface IGeminiBatchService {
    /**
     * Create a batch job for content generation requests.
     * 
     * @param requests List of content generation requests to batch
     * @return Batch job ID for polling status
     */
    suspend fun createContentBatch(requests: List<BatchContentRequest>): String

    /**
     * Create a batch job for embedding requests.
     * 
     * @param requests List of embedding requests to batch
     * @return Batch job ID for polling status
     */
    suspend fun createEmbeddingBatch(requests: List<BatchEmbeddingRequest>): String

    /**
     * Poll the status of a batch job.
     * 
     * @param batchJobId The batch job ID to check
     * @return Current status of the batch job
     */
    suspend fun pollBatchStatus(batchJobId: String): BatchJobStatus

    /**
     * Fetch results from a completed batch job.
     * 
     * @param batchJobId The batch job ID to fetch results for
     * @return List of batch results
     */
    suspend fun fetchBatchResults(batchJobId: String): List<BatchResult>

    /**
     * Cancel a running batch job.
     * 
     * @param batchJobId The batch job ID to cancel
     */
    suspend fun cancelBatch(batchJobId: String)
}

/**
 * A single content generation request in a batch.
 */
@Serializable
data class BatchContentRequest(
    /** Unique identifier for this request (used to match results) */
    val requestId: String,
    /** The model to use (e.g., "gemini-3.5-flash-lite") */
    val modelId: String,
    /** System instruction for the model */
    val systemInstruction: String?,
    /** User prompt/content */
    val userPrompt: String,
    /** Optional image data (base64 encoded) */
    val imageData: String? = null,
    /** Optional image MIME type */
    val imageMimeType: String? = null,
    /** Optional metadata for client-side use (not sent to the API) */
    val metadata: Map<String, String>? = null
) {
    /**
     * Response schema for structured output.
     * Transient because Schema is not serializable, but we need it for batch API calls.
     * Set via the withSchema() method.
     */
    @Transient
    var schema: Schema? = null
        private set
    
    /**
     * Create a copy of this request with the specified schema.
     */
    fun withSchema(schema: Schema): BatchContentRequest {
        val copy = this.copy()
        copy.schema = schema
        return copy
    }
}

/**
 * A single embedding request in a batch.
 */
@Serializable
data class BatchEmbeddingRequest(
    /** Unique identifier for this request (used to match results) */
    val requestId: String,
    /** The model to use (e.g., "gemini-embedding-2-preview") */
    val modelId: String,
    /** Text to embed */
    val text: String,
    /** Task type (e.g., "RETRIEVAL_DOCUMENT", "RETRIEVAL_QUERY") */
    val taskType: String,
    /** Output dimensionality (e.g., 1536) */
    val outputDimensionality: Int = 1536
)

/**
 * Status of a batch job.
 */
@OptIn(ExperimentalTime::class)
@Serializable
data class BatchJobStatus(
    val batchJobId: String,
    val state: BatchJobState,
    val totalRequests: Int,
    val completedRequests: Int,
    val failedRequests: Int,
    val createdAt: Long, // Epoch millis
    val updatedAt: Long, // Epoch millis
    val errorMessage: String? = null
) {
    fun isTerminal(): Boolean = state in listOf(
        BatchJobState.SUCCEEDED,
        BatchJobState.FAILED,
        BatchJobState.CANCELLED
    )

    fun isSuccess(): Boolean = state == BatchJobState.SUCCEEDED
}

/**
 * Batch job states as defined by Gemini API.
 */
enum class BatchJobState {
    /** Job is queued and waiting to start */
    PENDING,
    /** Job is currently running */
    RUNNING,
    /** Job completed successfully */
    SUCCEEDED,
    /** Job failed */
    FAILED,
    /** Job was cancelled */
    CANCELLED,
    /** Unknown state */
    UNKNOWN
}

/**
 * Result from a batch job.
 */
@Serializable
data class BatchResult(
    /** Request ID that this result corresponds to */
    val requestId: String,
    /** Whether this individual request succeeded */
    val success: Boolean,
    /** Generated text content (for content generation) */
    val generatedText: String? = null,
    /** Embedding vector (for embedding requests) */
    val embedding: List<Float>? = null,
    /** Error message if this request failed */
    val errorMessage: String? = null,
    /** Token usage for this request */
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0
)

