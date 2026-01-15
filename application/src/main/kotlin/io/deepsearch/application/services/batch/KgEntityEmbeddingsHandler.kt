package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IKnowledgeGraphIndexingService
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder,
    private val pollingService: BatchPollingService
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

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

        // Collect all KG extraction results from GCS
        val (extractionsByUrl, urlBasePaths) = collectKgExtractions(urlStates, jobId)

        if (extractionsByUrl.isEmpty()) {
            logger.info("[{}] No KG extractions to embed", jobId)
            cleanupGcsSnapshotData(jobId, urlBasePaths, urlStates)
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

        logger.info(
            "[{}] {} unique entities to embed from {} pages",
            jobId, allEntityNames.size, extractionsByUrl.size
        )

        if (allEntityNames.isEmpty()) {
            logger.info("[{}] No entity names to embed", jobId)
            cleanupGcsSnapshotData(jobId, urlBasePaths, urlStates)
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
            cleanupGcsSnapshotData(jobId, urlBasePaths, urlStates)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 5 complete: Could not prepare embedding requests")
            return
        }

        // Submit batch and poll until complete
        try {
            eventEmitter.emit(
                job, eventFlow,
                "Submitting entity embedding batch (${embeddingRequests.size} entities)..."
            )

            val batchJobId = geminiBatchService.createEmbeddingBatch(embeddingRequests)
            job.addBatchJob(batchJobId)
            batchJobRepository.update(job)

            logger.info("[{}] Submitted entity embedding batch: {}", jobId, batchJobId)

            // Poll until complete using shared polling service
            pollingService.pollUntilComplete(job, eventFlow, batchJobId, "entity embedding")

            // Fetch results
            val batchResults = geminiBatchService.fetchBatchResults(batchJobId)
            logger.info("[{}] Retrieved {} entity embedding results", jobId, batchResults.size)

            // Record token usage
            batchTokenUsageRecorder.recordBatchTokenUsage(
                jobId,
                "KgEntityEmbeddingBatch",
                "gemini-embedding-001",
                batchResults
            )

            // Build entity embeddings map
            val entityEmbeddings = mutableMapOf<String, List<Float>>()
            batchResults.forEachIndexed { index, result ->
                val entityName = allEntityNames.getOrNull(index) ?: return@forEachIndexed

                if (result.success && result.embedding != null) {
                    entityEmbeddings[entityName] = result.embedding!!
                } else {
                    logger.warn(
                        "[{}] Entity embedding failed for '{}': {}",
                        jobId, entityName, result.errorMessage
                    )
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

            eventEmitter.emit(
                job, eventFlow,
                "Stage 5 complete: $totalEntities entities, $totalRelationships relationships, " +
                    "${entityEmbeddings.size} embeddings"
            )

        } catch (e: Exception) {
            logger.error("[{}] Entity embedding batch failed: {}", jobId, e.message, e)
            job.clearBatchJobs()
            batchJobRepository.update(job)
            // Continue to completion - entity embeddings are not critical
            eventEmitter.emit(job, eventFlow, "Stage 5 complete (with errors): ${e.message}")
        }

        // Clean up GCS snapshot data after Stage 5 completes
        cleanupGcsSnapshotData(jobId, urlBasePaths, urlStates)

        job.advanceToNextStage()
        batchJobRepository.update(job)
    }

    /**
     * Collect KG extraction results from GCS for all cached URLs.
     *
     * @return Pair of (extractionsByUrl, urlBasePaths) for cleanup
     */
    private suspend fun collectKgExtractions(
        urlStates: List<BatchUrlState>,
        jobId: Long
    ): Pair<Map<String, KgExtractionResult>, Map<String, String>> {
        val extractionsByUrl = mutableMapOf<String, KgExtractionResult>()
        val urlBasePaths = mutableMapOf<String, String>() // url -> basePath for cleanup

        urlStates.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach
                urlBasePaths[urlState.url] = basePath

                val kgResult = snapshotStorage.readKgExtractionResult(basePath)
                if (kgResult != null && !kgResult.isEmpty()) {
                    extractionsByUrl[urlState.url] = kgResult
                }
            } catch (e: Exception) {
                logger.warn("[{}] Failed to read KG result for {}: {}", jobId, urlState.url, e.message)
            }
        }

        return Pair(extractionsByUrl, urlBasePaths)
    }

    /**
     * Delete GCS snapshot data for all processed URLs.
     * Also clears the snapshotBasePath from URL states.
     */
    private suspend fun cleanupGcsSnapshotData(
        jobId: Long,
        urlBasePaths: Map<String, String>,
        urlStates: List<BatchUrlState>
    ) {
        var deletedCount = 0
        urlBasePaths.forEach { (url, basePath) ->
            try {
                snapshotStorage.deleteUrl(basePath)
                deletedCount++
            } catch (e: Exception) {
                logger.warn("[{}] Failed to delete GCS snapshot for {}: {}", jobId, url, e.message)
            }
        }

        // Clear snapshotBasePath from URL states
        urlStates.forEach { urlState ->
            if (urlState.snapshotBasePath != null) {
                urlState.snapshotBasePath = null
                try {
                    batchUrlStateRepository.update(urlState)
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to clear snapshotBasePath for {}: {}", jobId, urlState.url, e.message)
                }
            }
        }

        logger.info("[{}] Cleaned up {} GCS snapshot directories", jobId, deletedCount)
    }
}
