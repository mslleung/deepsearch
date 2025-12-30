package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IKnowledgeGraphIndexingService
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stage 5: Knowledge graph extraction handler.
 * 
 * Extracts entities and relationships from cached markdown content
 * and indexes them to the knowledge graph using the Gemini Batch API.
 */
class KnowledgeGraphExtractionHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val knowledgeGraphIndexingService: IKnowledgeGraphIndexingService,
    private val eventEmitter: BatchEventEmitter
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val BATCH_POLL_INTERVAL_MS = 60_000L
        private const val MAX_BATCH_POLL_ATTEMPTS = 1440
        private const val MIN_MARKDOWN_LENGTH = 100
    }

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 5: Knowledge graph extraction", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 5: Extracting knowledge graph...")

        // Get all cached URLs
        val urlStates = batchUrlStateRepository.findByJobIdAndStage(jobId, BatchUrlProcessingStage.CACHED)
        logger.info("[{}] {} URLs available for KG extraction", jobId, urlStates.size)

        if (urlStates.isEmpty()) {
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 5 complete: No URLs to extract")
            return
        }

        // Prepare batch requests for entity extraction
        val batchRequests = mutableListOf<EntityExtractionBatchRequest>()
        
        urlStates.forEach { urlState ->
            try {
                val markdown = getMarkdownForUrl(urlState)
                if (markdown != null && markdown.length >= MIN_MARKDOWN_LENGTH) {
                    val requestId = "${jobId}-${urlState.id}-kg"
                    val request = knowledgeGraphIndexingService.prepareBatchRequest(
                        requestId = requestId,
                        markdown = markdown,
                        sourceUrl = urlState.url
                    )
                    batchRequests.add(EntityExtractionBatchRequest(
                        urlStateId = urlState.id!!,
                        url = urlState.url,
                        request = request
                    ))
                }
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare KG request for {}: {}", jobId, urlState.url, e.message)
            }
        }

        logger.info("[{}] Prepared {} KG extraction requests", jobId, batchRequests.size)

        if (batchRequests.isEmpty()) {
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 5 complete: No content for extraction")
            return
        }

        // Submit batch to Gemini API
        try {
            eventEmitter.emit(job, eventFlow, "Submitting KG extraction batch (${batchRequests.size} pages)...")

            val batchJobId = geminiBatchService.createContentBatch(batchRequests.map { it.request })
            job.setBatchJob(batchJobId)
            batchJobRepository.update(job)

            logger.info("[{}] Submitted KG extraction batch: {}", jobId, batchJobId)

            // Poll until complete
            pollBatchUntilComplete(job, eventFlow, batchJobId)

            // Process results
            val results = geminiBatchService.fetchBatchResults(batchJobId)
            val extractionResults = mutableMapOf<String, KgExtractionResult>()

            results.forEachIndexed { index, result ->
                val batchRequest = batchRequests.getOrNull(index) ?: return@forEachIndexed

                try {
                    if (!result.success || result.generatedText == null) {
                        logger.warn("[{}] KG batch failed for {}: {}", jobId, batchRequest.url, result.errorMessage)
                        return@forEachIndexed
                    }

                    val extraction = knowledgeGraphIndexingService.parseBatchResponse(result.generatedText!!)

                    if (!extraction.isEmpty()) {
                        extractionResults[batchRequest.url] = extraction
                    } else {
                        logger.debug("[{}] No entities extracted from {}", jobId, batchRequest.url)
                    }
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to parse KG result for {}: {}", jobId, batchRequest.url, e.message)
                }
            }

            // Store all results using the indexing service
            if (extractionResults.isNotEmpty()) {
                knowledgeGraphIndexingService.processBatchResults(extractionResults)
            }

            val entityCount = extractionResults.values.sumOf { it.entities.size }
            val relationshipCount = extractionResults.values.sumOf { it.relationships.size }
            logger.info(
                "[{}] KG extraction complete: {} pages, {} entities, {} relationships",
                jobId, extractionResults.size, entityCount, relationshipCount
            )

            job.clearBatchJob()
            batchJobRepository.update(job)

            eventEmitter.emit(
                job, eventFlow,
                "Stage 5 complete: ${extractionResults.size} pages, $entityCount entities, $relationshipCount relationships"
            )

        } catch (e: Exception) {
            logger.error("[{}] KG extraction batch failed: {}", jobId, e.message, e)
            job.clearBatchJob()
            batchJobRepository.update(job)
            // Continue to next stage even if KG extraction fails - it's not critical
            eventEmitter.emit(job, eventFlow, "Stage 5 complete (with errors): ${e.message}")
        }

        job.advanceToNextStage()
        batchJobRepository.update(job)
    }

    private fun getMarkdownForUrl(urlState: BatchUrlState): String? {
        val snapshotData = urlState.snapshotData?.let {
            try {
                json.decodeFromString<BatchUrlSnapshotData>(it)
            } catch (e: Exception) {
                null
            }
        }

        // Try to get markdown from snapshot data first
        return snapshotData?.markdown
    }

    private suspend fun pollBatchUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchJobId: String
    ) {
        var attempts = 0

        while (attempts < MAX_BATCH_POLL_ATTEMPTS) {
            try {
                val status = geminiBatchService.pollBatchStatus(batchJobId)

                when (status.state) {
                    BatchJobState.SUCCEEDED -> {
                        logger.info("Batch job {} completed successfully", batchJobId)
                        return
                    }

                    BatchJobState.FAILED -> throw RuntimeException("Batch job failed: ${status.errorMessage}")
                    BatchJobState.CANCELLED -> throw RuntimeException("Batch job was cancelled")
                    else -> {
                        eventEmitter.emit(
                            job,
                            eventFlow,
                            "Waiting for KG batch (${status.completedRequests}/${status.totalRequests})"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling, will retry")
                } else {
                    throw e
                }
            }

            delay(BATCH_POLL_INTERVAL_MS)
            attempts++
        }

        throw RuntimeException("Batch job polling timed out after $MAX_BATCH_POLL_ATTEMPTS attempts")
    }

    private data class EntityExtractionBatchRequest(
        val urlStateId: Long,
        val url: String,
        val request: io.deepsearch.domain.services.BatchContentRequest
    )
}

