package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IKnowledgeGraphIndexingService
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
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
 * Stage 5: Knowledge graph entity embeddings handler.
 * 
 * Generates embeddings for KG entities extracted in Stage 4 using the Gemini Batch API.
 * After embeddings are generated, stores the complete KG data (entities + relationships + embeddings)
 * in the knowledge graph repository.
 */
class KgEntityEmbeddingsHandler(
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
    }

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 5: KG entity embeddings", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 5: Generating entity embeddings...")

        // Get all cached URLs with KG extraction results
        val urlStates = batchUrlStateRepository.findByJobIdAndStage(jobId, BatchUrlProcessingStage.CACHED)
        logger.info("[{}] {} cached URLs available for entity embedding", jobId, urlStates.size)

        // Collect all KG extraction results from URL states
        val extractionsByUrl = mutableMapOf<String, KgExtractionResult>()
        
        urlStates.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                }
                
                val kgResult = snapshotData?.kgExtractionResult
                if (kgResult != null && !kgResult.isEmpty()) {
                    extractionsByUrl[urlState.url] = kgResult
                }
            } catch (e: Exception) {
                logger.warn("[{}] Failed to read KG result for {}: {}", jobId, urlState.url, e.message)
            }
        }

        if (extractionsByUrl.isEmpty()) {
            logger.info("[{}] No KG extractions to embed", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 5 complete: No entities to embed")
            return
        }

        // Collect all unique entity names for batch embedding
        val allEntityNames = extractionsByUrl.values
            .flatMap { it.entities }
            .map { it.name }
            .distinct()

        logger.info("[{}] {} unique entities to embed from {} pages", 
            jobId, allEntityNames.size, extractionsByUrl.size)

        if (allEntityNames.isEmpty()) {
            logger.info("[{}] No entity names to embed", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 5 complete: No entity names to embed")
            return
        }

        // Prepare batch embedding requests for entity names
        val embeddingRequests = knowledgeGraphIndexingService.prepareBatchEntityEmbeddingRequests(
            jobId = jobId,
            entityNames = allEntityNames
        )

        if (embeddingRequests.isEmpty()) {
            logger.warn("[{}] Failed to prepare entity embedding requests", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 5 complete: Could not prepare embedding requests")
            return
        }

        // Submit batch and poll until complete
        try {
            eventEmitter.emit(job, eventFlow, 
                "Submitting entity embedding batch (${embeddingRequests.size} entities)...")

            val batchJobId = geminiBatchService.createEmbeddingBatch(embeddingRequests)
            job.addBatchJob(batchJobId)
            batchJobRepository.update(job)

            logger.info("[{}] Submitted entity embedding batch: {}", jobId, batchJobId)

            // Poll until complete
            pollBatchUntilComplete(job, eventFlow, batchJobId)

            // Fetch results
            val batchResults = geminiBatchService.fetchBatchResults(batchJobId)
            logger.info("[{}] Retrieved {} entity embedding results", jobId, batchResults.size)

            // Build entity embeddings map
            val entityEmbeddings = mutableMapOf<String, List<Float>>()
            batchResults.forEachIndexed { index, result ->
                val entityName = allEntityNames.getOrNull(index) ?: return@forEachIndexed
                
                if (result.success && result.embedding != null) {
                    entityEmbeddings[entityName] = result.embedding!!
                } else {
                    logger.warn("[{}] Entity embedding failed for '{}': {}", 
                        jobId, entityName, result.errorMessage)
                }
            }

            logger.info("[{}] Successfully generated {} entity embeddings", jobId, entityEmbeddings.size)

            // Store KG data with embeddings
            if (entityEmbeddings.isNotEmpty()) {
                try {
                    knowledgeGraphIndexingService.processBatchEntityEmbeddingResults(
                        results = extractionsByUrl,
                        embeddings = entityEmbeddings
                    )
                    logger.info("[{}] Stored KG data with embeddings", jobId)
                } catch (e: Exception) {
                    logger.error("[{}] Failed to store KG data: {}", jobId, e.message, e)
                }
            }

            val totalEntities = extractionsByUrl.values.sumOf { it.entities.size }
            val totalRelationships = extractionsByUrl.values.sumOf { it.relationships.size }

            job.clearBatchJobs()
            batchJobRepository.update(job)

            eventEmitter.emit(job, eventFlow, 
                "Stage 5 complete: $totalEntities entities, $totalRelationships relationships, " +
                "${entityEmbeddings.size} embeddings")

        } catch (e: Exception) {
            logger.error("[{}] Entity embedding batch failed: {}", jobId, e.message, e)
            job.clearBatchJobs()
            batchJobRepository.update(job)
            // Continue to completion - entity embeddings are not critical
            eventEmitter.emit(job, eventFlow, "Stage 5 complete (with errors): ${e.message}")
        }

        job.advanceToNextStage()
        batchJobRepository.update(job)
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
                        logger.info("Entity embedding batch {} completed successfully", batchJobId)
                        return
                    }

                    BatchJobState.FAILED -> throw RuntimeException("Batch job failed: ${status.errorMessage}")
                    BatchJobState.CANCELLED -> throw RuntimeException("Batch job was cancelled")
                    else -> {
                        eventEmitter.emit(
                            job,
                            eventFlow,
                            "Waiting for entity embedding batch (${status.completedRequests}/${status.totalRequests})"
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
}

