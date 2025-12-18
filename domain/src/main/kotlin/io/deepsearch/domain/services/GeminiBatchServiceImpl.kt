package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.BatchJob
import com.google.genai.types.BatchJobSource
import com.google.genai.types.Content
import com.google.genai.types.CreateBatchJobConfig
import com.google.genai.types.CreateEmbeddingsBatchJobConfig
import com.google.genai.types.EmbedContentBatch
import com.google.genai.types.EmbedContentConfig
import com.google.genai.types.EmbeddingsBatchJobSource
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.InlinedRequest
import com.google.genai.types.JobState
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Implementation of IGeminiBatchService using the Gemini Batch API.
 * 
 * The Gemini Batch API provides 50% cost savings for large-scale processing,
 * with a target turnaround time of 24 hours (often faster).
 * 
 * This implementation uses inline requests for batches under 20MB.
 * For larger batches, file-based input should be used instead.
 * 
 * Note: The Batch API with inlined requests is only supported in the Gemini Developer API,
 * not Vertex AI. When using Vertex AI, GCS or BigQuery must be used instead.
 * 
 * @see https://ai.google.dev/gemini-api/docs/batch-api
 */
class GeminiBatchServiceImpl(
    private val client: Client
) : IGeminiBatchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun createContentBatch(requests: List<BatchContentRequest>): String {
        if (requests.isEmpty()) {
            throw IllegalArgumentException("Cannot create batch with empty requests")
        }

        logger.info("Creating content batch with {} requests", requests.size)

        // Convert BatchContentRequest to InlinedRequest
        val inlinedRequests = requests.map { request ->
            buildInlinedRequest(request)
        }

        // Create batch job source with inlined requests
        val source = BatchJobSource.builder()
            .inlinedRequests(inlinedRequests)
            .build()

        // Create batch job config with display name for tracking
        val config = CreateBatchJobConfig.builder()
            .displayName("deepsearch-content-batch-${System.currentTimeMillis()}")
            .build()

        // Get the model from the first request (all should use the same model)
        val modelId = requests.first().modelId

        val batchJob = withRateLimitRetry(this::class.simpleName!!) {
            client.batches.create(modelId, source, config)
        }

        val batchJobName = batchJob.name().orElseThrow {
            RuntimeException("Batch job created but no name returned")
        }

        logger.info("Created content batch job: {}", batchJobName)
        return batchJobName
    }

    override suspend fun createEmbeddingBatch(requests: List<BatchEmbeddingRequest>): String {
        if (requests.isEmpty()) {
            throw IllegalArgumentException("Cannot create embedding batch with empty requests")
        }

        logger.info("Creating embedding batch with {} requests", requests.size)

        // Convert BatchEmbeddingRequest to EmbedContentBatch
        // Each request becomes a Content object with the text to embed
        val contents = requests.map { request ->
            Content.builder()
                .parts(listOf(Part.fromText(request.text)))
                .role("user")
                .build()
        }

        // Build the embed content config
        val embedConfig = EmbedContentConfig.builder()
            .taskType(requests.first().taskType)
            .outputDimensionality(requests.first().outputDimensionality)
            .build()

        // Create the embed content batch
        val embedBatch = EmbedContentBatch.builder()
            .contents(contents)
            .config(embedConfig)
            .build()

        // Create batch job source
        val source = EmbeddingsBatchJobSource.builder()
            .inlinedRequests(embedBatch)
            .build()

        // Create batch job config
        val config = CreateEmbeddingsBatchJobConfig.builder()
            .displayName("deepsearch-embedding-batch-${System.currentTimeMillis()}")
            .build()

        // Get the model from the first request
        val modelId = requests.first().modelId

        val batchJob = withRateLimitRetry(this::class.simpleName!!) {
            client.batches.createEmbeddings(modelId, source, config)
        }

        val batchJobName = batchJob.name().orElseThrow {
            RuntimeException("Embedding batch job created but no name returned")
        }

        logger.info("Created embedding batch job: {}", batchJobName)
        return batchJobName
    }

    override suspend fun pollBatchStatus(batchJobId: String): BatchJobStatus {
        logger.debug("Polling batch job status: {}", batchJobId)

        val batchJob = withRateLimitRetry(this::class.simpleName!!) {
            client.batches.get(batchJobId, null)
        }

        val state = mapJobState(batchJob)
        
        // Extract completion stats if available
        val completionStats = batchJob.completionStats().orElse(null)
        val successCount = completionStats?.successfulCount()?.orElse(0L) ?: 0L
        val failCount = completionStats?.failedCount()?.orElse(0L) ?: 0L
        val totalRequests = (successCount + failCount).toInt()
        val completedRequests = successCount.toInt()
        val failedRequests = failCount.toInt()

        // Extract error message if present
        val errorMessage = batchJob.error()
            .flatMap { it.message() }
            .orElse(null)

        // Extract timestamps
        val createdAt = batchJob.createTime()
            .map { it.toEpochMilli() }
            .orElse(System.currentTimeMillis())
        val updatedAt = batchJob.updateTime()
            .map { it.toEpochMilli() }
            .orElse(System.currentTimeMillis())

        return BatchJobStatus(
            batchJobId = batchJobId,
            state = state,
            totalRequests = totalRequests,
            completedRequests = completedRequests,
            failedRequests = failedRequests,
            createdAt = createdAt,
            updatedAt = updatedAt,
            errorMessage = errorMessage
        )
    }

    override suspend fun fetchBatchResults(batchJobId: String): List<BatchResult> {
        logger.debug("Fetching results for batch job: {}", batchJobId)

        val batchJob = withRateLimitRetry(this::class.simpleName!!) {
            client.batches.get(batchJobId, null)
        }

        // Check if batch is complete
        val state = mapJobState(batchJob)
        if (!state.isTerminal()) {
            logger.warn("Attempted to fetch results for non-terminal batch job: {} (state: {})", batchJobId, state)
            return emptyList()
        }

        val dest = batchJob.dest().orElse(null)
        if (dest == null) {
            logger.warn("No destination found for batch job: {}", batchJobId)
            return emptyList()
        }
        
        // Try to get inlined responses (for content generation)
        val inlinedResponses = dest.inlinedResponses().orElse(null)
        if (inlinedResponses != null && inlinedResponses.isNotEmpty()) {
            logger.debug("Found {} inlined responses", inlinedResponses.size)
            return inlinedResponses.mapIndexed { index, response ->
                val contentResponse = response.response().orElse(null)
                val generatedText = contentResponse?.text()
                
                val error = response.error()
                    .flatMap { it.message() }
                    .orElse(null)

                // Extract token usage from response
                val usageMetadata = contentResponse?.usageMetadata()?.orElse(null)

                BatchResult(
                    requestId = "request-$index", // Inlined requests use index-based matching
                    success = generatedText != null && error == null,
                    generatedText = generatedText,
                    embedding = null,
                    errorMessage = error,
                    promptTokens = usageMetadata?.promptTokenCount()?.orElse(0) ?: 0,
                    outputTokens = usageMetadata?.candidatesTokenCount()?.orElse(0) ?: 0,
                    totalTokens = usageMetadata?.totalTokenCount()?.orElse(0) ?: 0
                )
            }
        }

        // Try to get inlined embedding responses
        val inlinedEmbedResponses = dest.inlinedEmbedContentResponses().orElse(null)
        if (inlinedEmbedResponses != null && inlinedEmbedResponses.isNotEmpty()) {
            logger.debug("Found {} inlined embedding responses", inlinedEmbedResponses.size)
            return inlinedEmbedResponses.mapIndexed { index, response ->
                val embedResponse = response.response().orElse(null)
                val embedding = embedResponse?.embedding()
                    ?.flatMap { it.values() }
                    ?.orElse(null)

                val error = response.error()
                    .flatMap { it.message() }
                    .orElse(null)

                BatchResult(
                    requestId = "request-$index",
                    success = embedding != null && error == null,
                    generatedText = null,
                    embedding = embedding,
                    errorMessage = error,
                    promptTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0
                )
            }
        }

        logger.warn("No inlined responses found for batch job: {}", batchJobId)
        return emptyList()
    }

    override suspend fun cancelBatch(batchJobId: String) {
        logger.info("Cancelling batch job: {}", batchJobId)

        try {
            withRateLimitRetry(this::class.simpleName!!) {
                client.batches.cancel(batchJobId, null)
            }
            logger.info("Successfully cancelled batch job: {}", batchJobId)
        } catch (e: Exception) {
            logger.warn("Failed to cancel batch job {}: {}", batchJobId, e.message)
            // Don't rethrow - cancellation failures are not critical
        }
    }

    /**
     * Build an InlinedRequest from a BatchContentRequest.
     */
    private fun buildInlinedRequest(request: BatchContentRequest): InlinedRequest {
        // Build the content parts
        val parts = mutableListOf<Part>()
        
        // Add image if present
        if (request.imageData != null && request.imageMimeType != null) {
            val imageBytes = java.util.Base64.getDecoder().decode(request.imageData)
            parts.add(Part.fromBytes(imageBytes, request.imageMimeType))
        }
        
        // Add text prompt
        parts.add(Part.fromText(request.userPrompt))

        val userContent = Content.builder()
            .parts(parts)
            .role("user")
            .build()

        // Build generate content config
        val configBuilder = GenerateContentConfig.builder()
            .temperature(request.temperature)
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingBudget(0)
                    .build()
            )

        // Add system instruction if present
        if (request.systemInstruction != null) {
            configBuilder.systemInstruction(
                Content.fromParts(Part.fromText(request.systemInstruction))
            )
        }

        // Add response schema if present (for structured JSON output)
        if (request.schema != null) {
            configBuilder.responseMimeType("application/json")
            configBuilder.responseSchema(request.schema)
        }

        // Build the inlined request
        // Use metadata to store the request ID for matching responses
        return InlinedRequest.builder()
            .model(request.modelId)
            .contents(listOf(userContent))
            .config(configBuilder.build())
            .metadata(mapOf("requestId" to request.requestId))
            .build()
    }

    /**
     * Map Gemini JobState to our BatchJobState enum.
     */
    private fun mapJobState(batchJob: BatchJob): BatchJobState {
        val jobState = batchJob.state().orElse(null)
        if (jobState == null) {
            return BatchJobState.UNKNOWN
        }

        return when (jobState.knownEnum()) {
            JobState.Known.JOB_STATE_QUEUED,
            JobState.Known.JOB_STATE_PENDING -> BatchJobState.PENDING
            
            JobState.Known.JOB_STATE_RUNNING,
            JobState.Known.JOB_STATE_UPDATING -> BatchJobState.RUNNING
            
            JobState.Known.JOB_STATE_SUCCEEDED -> BatchJobState.SUCCEEDED
            
            JobState.Known.JOB_STATE_PARTIALLY_SUCCEEDED -> BatchJobState.SUCCEEDED // Treat as success, check individual results
            
            JobState.Known.JOB_STATE_FAILED,
            JobState.Known.JOB_STATE_EXPIRED -> BatchJobState.FAILED
            
            JobState.Known.JOB_STATE_CANCELLED,
            JobState.Known.JOB_STATE_CANCELLING -> BatchJobState.CANCELLED
            
            JobState.Known.JOB_STATE_PAUSED -> BatchJobState.PENDING // Paused is like pending
            
            JobState.Known.JOB_STATE_UNSPECIFIED -> BatchJobState.UNKNOWN
        }
    }

    /**
     * Helper extension to check if a batch state is terminal.
     */
    private fun BatchJobState.isTerminal(): Boolean = this in listOf(
        BatchJobState.SUCCEEDED,
        BatchJobState.FAILED,
        BatchJobState.CANCELLED
    )
}
