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
import com.google.genai.types.UploadFileConfig
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.createTempFile

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

    companion object {
        /**
         * Maximum size in bytes for inline batch requests.
         * The Gemini API has a 20MB limit; we use 15MB for safety margin.
         */
        private const val MAX_INLINE_SIZE_BYTES = 15 * 1024 * 1024L // 15MB
    }

    /**
     * Calculate the exact size of batch requests in bytes by serializing to JSON.
     * Returns a pair of (totalSizeBytes, serializedJsonLines) so we can reuse
     * the serialized content if file-based approach is needed.
     */
    private fun calculateBatchSize(requests: List<BatchContentRequest>): Pair<Long, List<String>> {
        val jsonLines = requests.map { request ->
            val jsonObject = buildJsonLineRequest(request)
            Json.encodeToString(JsonObject.serializer(), jsonObject)
        }
        val totalSize = jsonLines.sumOf { it.length.toLong() + 1L } // +1 for newline
        return totalSize to jsonLines
    }

    /**
     * Calculate the exact size of embedding batch requests in bytes by serializing to JSON.
     * Returns a pair of (totalSizeBytes, serializedJsonLines) so we can reuse
     * the serialized content if file-based approach is needed.
     */
    private fun calculateEmbeddingBatchSize(requests: List<BatchEmbeddingRequest>): Pair<Long, List<String>> {
        val jsonLines = requests.map { request ->
            val jsonObject = buildJsonObject {
                put("key", request.requestId)
                putJsonObject("request") {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", request.text)
                            })
                        }
                    }
                    putJsonObject("config") {
                        put("task_type", request.taskType)
                        put("output_dimensionality", request.outputDimensionality)
                    }
                }
            }
            Json.encodeToString(JsonObject.serializer(), jsonObject)
        }
        val totalSize = jsonLines.sumOf { it.length.toLong() + 1L } // +1 for newline
        return totalSize to jsonLines
    }

    // ==================== File-Based Batch Methods ====================

    /**
     * Build a JSON object representing a single request for JSONL file format.
     * Format: {"key": "request-id", "request": {...}}
     */
    private fun buildJsonLineRequest(request: BatchContentRequest): JsonObject {
        // Build contents array with parts
        val parts = mutableListOf<JsonObject>()
        
        // Add image part if present
        if (request.imageData != null && request.imageMimeType != null) {
            parts.add(buildJsonObject {
                putJsonObject("inline_data") {
                    put("mime_type", request.imageMimeType)
                    put("data", request.imageData)
                }
            })
        }
        
        // Add text part
        parts.add(buildJsonObject {
            put("text", request.userPrompt)
        })
        
        // Build the request object
        return buildJsonObject {
            put("key", request.requestId)
            putJsonObject("request") {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            parts.forEach { add(it) }
                        }
                    })
                }
                
                // Add system instruction if present
                if (request.systemInstruction != null) {
                    putJsonObject("system_instruction") {
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", request.systemInstruction)
                            })
                        }
                    }
                }
                
                // Add generation config
                putJsonObject("generation_config") {
                    put("temperature", request.temperature.toDouble())
                    // Note: Response schema is not directly serializable, 
                    // but for file-based batches we may need to handle this differently
                    if (request.schema != null) {
                        put("response_mime_type", "application/json")
                    }
                }
            }
        }
    }

    /**
     * Create a content batch using file-based upload for large batches.
     * 
     * @param modelId The model ID to use for the batch
     * @param serializedJsonLines Pre-serialized JSON lines (one per request)
     * @return The batch job ID
     */
    private suspend fun createContentBatchWithFile(modelId: String, serializedJsonLines: List<String>): String {
        val timestamp = System.currentTimeMillis()
        
        logger.info("Creating file-based content batch with {} requests (model: {})", serializedJsonLines.size, modelId)
        
        // Create temp file for JSONL content
        val tempFile = createTempFile(prefix = "batch-requests-$timestamp", suffix = ".jsonl")
        
        try {
            // Write pre-serialized JSONL content
            tempFile.bufferedWriter().use { writer ->
                serializedJsonLines.forEach { jsonLine ->
                    writer.write(jsonLine)
                    writer.newLine()
                }
            }
            
            logger.debug("Created JSONL file: {} ({} bytes)", tempFile.absolutePath, tempFile.length())
            
            // Upload file to Gemini Files API
            val uploadConfig = UploadFileConfig.builder()
                .displayName("deepsearch-batch-$timestamp.jsonl")
                .mimeType("application/jsonl")
                .build()
            
            val uploadedFile = withRateLimitRetry(this::class.simpleName!!) {
                client.files.upload(tempFile, uploadConfig)
            }
            
            val uploadedFileName = uploadedFile.name().orElseThrow {
                RuntimeException("File uploaded but no name returned")
            }
            
            logger.info("Uploaded batch file: {}", uploadedFileName)
            
            // Create batch job with file reference
            val source = BatchJobSource.builder()
                .fileName(uploadedFileName)
                .build()
            
            val config = CreateBatchJobConfig.builder()
                .displayName("deepsearch-content-batch-file-$timestamp")
                .build()
            
            val batchJob = withRateLimitRetry(this::class.simpleName!!) {
                client.batches.create(modelId, source, config)
            }
            
            val batchJobName = batchJob.name().orElseThrow {
                RuntimeException("Batch job created but no name returned")
            }
            
            logger.info("Created file-based content batch job: {}", batchJobName)
            return batchJobName
            
        } finally {
            // Clean up temp file
            try {
                tempFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to delete temp file: {}", tempFile.absolutePath)
            }
        }
    }

    /**
     * Create a content batch using inline requests for small batches.
     * 
     * @param requests The batch content requests
     * @return The batch job ID
     */
    private suspend fun createContentBatchInline(requests: List<BatchContentRequest>): String {
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

        logger.info("Created inline content batch job: {}", batchJobName)
        return batchJobName
    }

    // ==================== Public API Methods ====================

    override suspend fun createContentBatch(requests: List<BatchContentRequest>): String {
        if (requests.isEmpty()) {
            throw IllegalArgumentException("Cannot create batch with empty requests")
        }

        // Calculate exact size by serializing to JSON
        val (exactSize, serializedLines) = calculateBatchSize(requests)
        val exactSizeMB = exactSize / (1024.0 * 1024.0)
        
        logger.info(
            "Creating content batch with {} requests (exact size: {:.2f} MB)", 
            requests.size, 
            exactSizeMB
        )

        // Choose inline or file-based approach based on exact size
        return if (exactSize < MAX_INLINE_SIZE_BYTES) {
            logger.debug("Using inline approach (under {}MB limit)", MAX_INLINE_SIZE_BYTES / (1024 * 1024))
            createContentBatchInline(requests)
        } else {
            logger.info("Using file-based approach ({:.2f}MB exceeds {}MB limit)", 
                exactSizeMB,
                MAX_INLINE_SIZE_BYTES / (1024 * 1024)
            )
            // Reuse the already-serialized JSON lines
            createContentBatchWithFile(requests.first().modelId, serializedLines)
        }
    }

    override suspend fun createEmbeddingBatch(requests: List<BatchEmbeddingRequest>): String {
        if (requests.isEmpty()) {
            throw IllegalArgumentException("Cannot create embedding batch with empty requests")
        }

        // Calculate exact size by serializing to JSON
        val (exactSize, serializedLines) = calculateEmbeddingBatchSize(requests)
        val exactSizeMB = exactSize / (1024.0 * 1024.0)
        
        logger.info(
            "Creating embedding batch with {} requests (exact size: {:.2f} MB)", 
            requests.size, 
            exactSizeMB
        )

        // Choose inline or file-based approach based on exact size
        return if (exactSize < MAX_INLINE_SIZE_BYTES) {
            logger.debug("Using inline approach for embeddings (under {}MB limit)", MAX_INLINE_SIZE_BYTES / (1024 * 1024))
            createEmbeddingBatchInline(requests)
        } else {
            logger.info("Using file-based approach for embeddings ({:.2f}MB exceeds {}MB limit)", 
                exactSizeMB,
                MAX_INLINE_SIZE_BYTES / (1024 * 1024)
            )
            // Reuse the already-serialized JSON lines
            createEmbeddingBatchWithFile(requests.first().modelId, serializedLines)
        }
    }

    /**
     * Create an embedding batch using inline requests for small batches.
     */
    private suspend fun createEmbeddingBatchInline(requests: List<BatchEmbeddingRequest>): String {
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

        logger.info("Created inline embedding batch job: {}", batchJobName)
        return batchJobName
    }

    /**
     * Create an embedding batch using file-based upload for large batches.
     * 
     * @param modelId The model ID to use for the batch
     * @param serializedJsonLines Pre-serialized JSON lines (one per request)
     * @return The batch job ID
     */
    private suspend fun createEmbeddingBatchWithFile(modelId: String, serializedJsonLines: List<String>): String {
        val timestamp = System.currentTimeMillis()
        
        logger.info("Creating file-based embedding batch with {} requests (model: {})", serializedJsonLines.size, modelId)
        
        // Create temp file for JSONL content
        val tempFile = createTempFile(prefix = "batch-embedding-requests-$timestamp", suffix = ".jsonl")
        
        try {
            // Write pre-serialized JSONL content
            tempFile.bufferedWriter().use { writer ->
                serializedJsonLines.forEach { jsonLine ->
                    writer.write(jsonLine)
                    writer.newLine()
                }
            }
            
            logger.debug("Created embedding JSONL file: {} ({} bytes)", tempFile.absolutePath, tempFile.length())
            
            // Upload file to Gemini Files API
            val uploadConfig = UploadFileConfig.builder()
                .displayName("deepsearch-embedding-batch-$timestamp.jsonl")
                .mimeType("application/jsonl")
                .build()
            
            val uploadedFile = withRateLimitRetry(this::class.simpleName!!) {
                client.files.upload(tempFile, uploadConfig)
            }
            
            val uploadedFileName = uploadedFile.name().orElseThrow {
                RuntimeException("File uploaded but no name returned")
            }
            
            logger.info("Uploaded embedding batch file: {}", uploadedFileName)
            
            // Create batch job with file reference
            val source = EmbeddingsBatchJobSource.builder()
                .fileName(uploadedFileName)
                .build()
            
            val config = CreateEmbeddingsBatchJobConfig.builder()
                .displayName("deepsearch-embedding-batch-file-$timestamp")
                .build()
            
            val batchJob = withRateLimitRetry(this::class.simpleName!!) {
                client.batches.createEmbeddings(modelId, source, config)
            }
            
            val batchJobName = batchJob.name().orElseThrow {
                RuntimeException("Embedding batch job created but no name returned")
            }
            
            logger.info("Created file-based embedding batch job: {}", batchJobName)
            return batchJobName
            
        } finally {
            // Clean up temp file
            try {
                tempFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to delete temp file: {}", tempFile.absolutePath)
            }
        }
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

        // Try to get file-based responses (for large batches)
        val resultFileName = dest.fileName().orElse(null)
        if (resultFileName != null) {
            logger.debug("Found file-based responses in: {}", resultFileName)
            return fetchFileBasedResults(resultFileName)
        }

        logger.warn("No inlined or file-based responses found for batch job: {}", batchJobId)
        return emptyList()
    }

    /**
     * Fetch and parse results from a file-based batch response.
     * 
     * The result file is a JSONL file where each line contains:
     * - For content generation: {"key": "request-id", "response": {...}} or {"key": "request-id", "error": {...}}
     * - For embeddings: {"key": "request-id", "response": {"embedding": {...}}} or {"key": "request-id", "error": {...}}
     */
    private suspend fun fetchFileBasedResults(fileName: String): List<BatchResult> {
        logger.info("Downloading batch results from file: {}", fileName)
        
        // Create temp file for download
        val tempFile = createTempFile(prefix = "batch-results-", suffix = ".jsonl")
        
        try {
            // Download the file content to temp file
            withRateLimitRetry(this::class.simpleName!!) {
                client.files.download(fileName, tempFile.absolutePath, null)
            }
            
            val fileContent = tempFile.readText()
            val results = mutableListOf<BatchResult>()
            
            // Parse each JSONL line
            fileContent.lines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                try {
                    val jsonElement = Json.parseToJsonElement(line)
                    if (jsonElement is JsonObject) {
                        val key = (jsonElement["key"] as? JsonPrimitive)?.content ?: "request-$index"
                        
                        // Check for response
                        val responseObj = jsonElement["response"] as? JsonObject
                        val errorObj = jsonElement["error"] as? JsonObject
                        
                        if (responseObj != null) {
                            // Check if this is an embedding response
                            val embeddingObj = responseObj["embedding"] as? JsonObject
                            if (embeddingObj != null) {
                                // Parse embedding response
                                val valuesArray = embeddingObj["values"] as? JsonArray
                                val embedding = valuesArray?.mapNotNull { value ->
                                    (value as? JsonPrimitive)?.content?.toFloatOrNull()
                                }
                                
                                results.add(BatchResult(
                                    requestId = key,
                                    success = embedding != null && embedding.isNotEmpty(),
                                    generatedText = null,
                                    embedding = embedding,
                                    errorMessage = null,
                                    promptTokens = 0,
                                    outputTokens = 0,
                                    totalTokens = 0
                                ))
                            } else {
                                // Parse content generation response
                                val candidates = responseObj["candidates"] as? JsonArray
                                val firstCandidate = candidates?.firstOrNull() as? JsonObject
                                val content = firstCandidate?.get("content") as? JsonObject
                                val parts = content?.get("parts") as? JsonArray
                                val textPart = parts?.firstOrNull { part ->
                                    (part as? JsonObject)?.containsKey("text") == true
                                } as? JsonObject
                                val generatedText = (textPart?.get("text") as? JsonPrimitive)?.content
                                
                                // Extract usage metadata
                                val usageMetadata = responseObj["usageMetadata"] as? JsonObject
                                val promptTokens = (usageMetadata?.get("promptTokenCount") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                                val candidatesTokens = (usageMetadata?.get("candidatesTokenCount") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                                val totalTokens = (usageMetadata?.get("totalTokenCount") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                                
                                results.add(BatchResult(
                                    requestId = key,
                                    success = generatedText != null,
                                    generatedText = generatedText,
                                    embedding = null,
                                    errorMessage = null,
                                    promptTokens = promptTokens,
                                    outputTokens = candidatesTokens,
                                    totalTokens = totalTokens
                                ))
                            }
                        } else if (errorObj != null) {
                            // Extract error message
                            val errorMessage = (errorObj["message"] as? JsonPrimitive)?.content
                                ?: "Unknown error"
                            
                            results.add(BatchResult(
                                requestId = key,
                                success = false,
                                generatedText = null,
                                embedding = null,
                                errorMessage = errorMessage,
                                promptTokens = 0,
                                outputTokens = 0,
                                totalTokens = 0
                            ))
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse batch result line {}: {}", index, e.message)
                }
            }
            
            logger.info("Parsed {} results from file", results.size)
            return results
            
        } catch (e: Exception) {
            logger.error("Failed to download batch results file {}: {}", fileName, e.message)
            throw RuntimeException("Failed to download batch results: ${e.message}", e)
        } finally {
            // Clean up temp file
            try {
                tempFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to delete temp results file: {}", tempFile.absolutePath)
            }
        }
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
