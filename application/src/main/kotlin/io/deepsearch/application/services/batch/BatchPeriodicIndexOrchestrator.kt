package io.deepsearch.application.services.batch

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.Job
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
 * Orchestrates the 5-stage batch periodic index pipeline.
 * 
 * Stage 1: CRAWL_AND_EXTRACT - Browser-based crawl + extraction (single visit per URL)
 * Stage 2: CONTENT_LLM_BATCH - LLM batch for semantic/table/icon identification
 * Stage 3: LLM_TABLE_INTERPRETATION - LLM batch for table interpretation
 * Stage 4: FINALIZE_AND_CACHE_EMBEDDING - Finalize markdown and generate embeddings
 * Stage 5: KNOWLEDGE_GRAPH_EXTRACTION - LLM batch for entity and relationship extraction
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
    private val finalizeAndCacheHandler: FinalizeAndCacheHandler,
    private val knowledgeGraphExtractionHandler: KnowledgeGraphExtractionHandler,
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
        
        // Cancel any active Gemini batch job
        job.geminiBatchJobId?.let { batchId ->
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
        
        try {
            job.markResumed()
            batchJobRepository.update(job)
            
            emitEvent(job, eventFlow, "Starting batch pipeline")

            // Run stages based on current state
            while (!job.isTerminal()) {
                when (job.state) {
                    BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT -> 
                        crawlAndExtractHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.CONTENT_LLM_BATCH -> 
                        contentLlmBatchHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.LLM_TABLE_INTERPRETATION -> 
                        tableInterpretationHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.FINALIZE_AND_CACHE_EMBEDDING -> 
                        finalizeAndCacheHandler.execute(job, eventFlow)
                    BatchPeriodicIndexJobState.KNOWLEDGE_GRAPH_EXTRACTION -> 
                        knowledgeGraphExtractionHandler.execute(job, eventFlow)
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
}

