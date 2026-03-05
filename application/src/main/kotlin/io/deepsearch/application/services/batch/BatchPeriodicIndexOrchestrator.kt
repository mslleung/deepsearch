package io.deepsearch.application.services.batch

import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.entities.BatchPipelineMode
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for batch periodic index orchestration.
 */
interface IBatchPeriodicIndexOrchestrator {
    /**
     * Start or resume a batch periodic index job.
     */
    suspend fun startOrResume(job: BatchPeriodicIndexJob)

    /**
     * Stop a running batch job.
     */
    suspend fun stop(jobId: Long)

    /**
     * Get the event stream for a job.
     */
    fun events(jobId: Long): SharedFlow<BatchPeriodicIndexEvent>
}

/**
 * Orchestrates the 6-stage batch periodic index pipeline.
 * 
 * HTML Pipeline (Stages 1-5):
 * Stage 1: CRAWL_AND_EXTRACT - Browser-based crawl + extraction (single visit per URL)
 *          + FILE URLs are downloaded and queued for background upload
 * Stage 2: CONTENT_LLM_BATCH - LLM batch for semantic/table/icon identification (HTML only)
 * Stage 3: LLM_TABLE_INTERPRETATION - LLM batch for table interpretation (HTML only)
 * Stage 4: PARALLEL_EMBEDDING_AND_KG_EXTRACTION - Page embedding batch + KG extraction batch (HTML only)
 * Stage 5: KG_ENTITY_EMBEDDINGS - Batch embedding for extracted KG entities
 * Stage 6: PROCESSING_FILES - Wait for any remaining file uploads to complete
 * 
 * FILE UPLOAD: A background worker runs independently throughout stages 1-5,
 * uploading discovered files to Gemini File Search without blocking the HTML pipeline.
 * Stage 6 ensures all files are uploaded before marking the job as complete.
 * 
 * Each stage is handled by a dedicated handler class.
 * State is persisted at each stage for resumption after server restarts.
 */
class BatchPeriodicIndexOrchestrator(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val dispatchers: IDispatcherProvider,
    private val applicationScope: IApplicationCoroutineScope,
    // Stage handlers
    private val crawlAndExtractHandler: CrawlAndExtractHandler,
    private val contentLlmBatchHandler: ContentLlmBatchHandler,
    private val tableInterpretationHandler: TableInterpretationBatchHandler,
    private val parallelEmbeddingAndKgHandler: ParallelEmbeddingAndKgHandler,
    private val kgEntityEmbeddingsHandler: KgEntityEmbeddingsHandler,
    private val lightweightIndexHandler: LightweightIndexHandler,
    // Background workers
    private val fileUploadBackgroundWorker: FileUploadBackgroundWorker,
    private val eventEmitter: BatchEventEmitter
) : IBatchPeriodicIndexOrchestrator {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class Run(
        val eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        val coroutineJob: Job
    )

    private val runs = ConcurrentHashMap<Long, Run>()

    init {
        // Resume all in-progress batch jobs at startup
        applicationScope.scope.launch {
            val activeJobs = batchJobRepository.findActiveJobs()
            logger.info("Found {} active batch jobs to resume", activeJobs.size)
            activeJobs.forEach { job ->
                startOrResume(job)
            }
        }
    }

    override suspend fun startOrResume(job: BatchPeriodicIndexJob) {
        val jobId = requireNotNull(job.id) { "Job must have an ID" }
        
        runs.compute(jobId) { _, existing ->
            if (existing == null || !existing.coroutineJob.isActive) {
                val eventFlow = MutableSharedFlow<BatchPeriodicIndexEvent>(replay = 1)
                val coroutineJob = applicationScope.scope.launch(dispatchers.io) {
                    runPipeline(job, eventFlow)
                }
                Run(eventFlow, coroutineJob)
            } else {
                existing
            }
        }
    }

    override suspend fun stop(jobId: Long) {
        runs.remove(jobId)?.coroutineJob?.cancel()
        val job = batchJobRepository.findById(jobId) ?: return
        
        // Cancel all active Gemini batch jobs
        job.batchJobIds.forEach { batchId ->
            try {
                geminiBatchService.cancelBatch(batchId)
            } catch (e: Exception) {
                logger.warn("Failed to cancel Gemini batch job: {}", batchId, e)
            }
        }
        
        job.markStopped()
        batchJobRepository.update(job)
    }

    override fun events(jobId: Long): SharedFlow<BatchPeriodicIndexEvent> {
        return runs[jobId]?.eventFlow ?: MutableSharedFlow(replay = 1)
    }

    private suspend fun runPipeline(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        
        // Start background file upload worker - runs independently throughout the pipeline
        val fileUploadJob = fileUploadBackgroundWorker.start(
            jobId, 
            CoroutineScope(currentCoroutineContext())
        )
        
        try {
            job.markResumed()
            batchJobRepository.update(job)
            
            emitEvent(job, eventFlow, "Starting batch pipeline")

            // Run stages based on current state
            // Note: FILE processing happens completely independently via fileUploadBackgroundWorker
            // The main pipeline (Stages 1-5) only handles HTML URLs
            // Stage 6 waits for any remaining file uploads to complete
            while (!job.isTerminal()) {
                when (job.state) {
                    BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> 
                        crawlAndExtractHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.CONTENT_LLM_BATCH -> {
                        if (job.pipelineMode == BatchPipelineMode.LIGHTWEIGHT) {
                            lightweightIndexHandler.execute(job, eventFlow)
                        } else {
                            contentLlmBatchHandler.execute(job, eventFlow)
                        }
                    }
                    BatchPeriodicIndexJobState.LLM_TABLE_INTERPRETATION -> 
                        tableInterpretationHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.PARALLEL_EMBEDDING_AND_KG_EXTRACTION ->
                        parallelEmbeddingAndKgHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.KG_ENTITY_EMBEDDINGS -> 
                        kgEntityEmbeddingsHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.PROCESSING_FILES ->
                        waitForFileUploads(job, eventFlow)
                    else -> break
                }
            }

            if (job.state == BatchPeriodicIndexJobState.COMPLETED) {
                emitEvent(job, eventFlow, "Batch pipeline completed successfully")
            }
        } catch (e: Exception) {
            logger.error("Batch pipeline failed for job {}: {}", jobId, e.message, e)
            job.markFailed(e.message ?: "Unknown error")
            batchJobRepository.update(job)
            emitEvent(job, eventFlow, "Pipeline failed: ${e.message}")
        } finally {
            // Stop the background file upload worker
            fileUploadJob.cancel()
            runs.remove(jobId)
        }
    }

    private suspend fun emitEvent(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        message: String
    ) {
        eventEmitter.emit(job, eventFlow, message)
    }

    /**
     * Stage 6: Wait for any remaining file uploads to complete.
     * 
     * This ensures all discovered files are uploaded to Gemini File Search
     * before marking the batch job as complete.
     */
    private suspend fun waitForFileUploads(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 6: Waiting for file uploads to complete", jobId)
        
        while (true) {
            val counts = batchUrlStateRepository.countByStage(jobId)
            
            if (counts.pendingFileUpload == 0) {
                // All files uploaded (or no files at all)
                val totalFiles = counts.fileUploaded
                if (totalFiles > 0) {
                    logger.info("[{}] All {} files uploaded to Gemini File Search", jobId, totalFiles)
                    emitEvent(job, eventFlow, "Stage 6 complete: $totalFiles files uploaded")
                } else {
                    emitEvent(job, eventFlow, "Stage 6 complete: No files to upload")
                }
                
                job.advanceToNextStage()
                batchJobRepository.update(job)
                return
            }
            
            // Files still uploading - emit progress and wait
            val uploaded = counts.fileUploaded
            val total = uploaded + counts.pendingFileUpload
            logger.debug("[{}] File uploads: {}/{} complete", jobId, uploaded, total)
            emitEvent(job, eventFlow, "Uploading files to Gemini File Search: $uploaded/$total complete")
            
            delay(FILE_UPLOAD_POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val FILE_UPLOAD_POLL_INTERVAL_MS = 5_000L
    }
}

