package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.INormalizeUrlService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Service interface for managing batch periodic index jobs.
 * 
 * Batch jobs are created internally by the backend scheduler for recurring
 * periodic index jobs. Users cannot trigger batch jobs directly - they use
 * the interactive method for "trigger now" which provides instant feedback.
 */
interface IBatchPeriodicIndexJobService {
    /**
     * Start a new batch periodic index job.
     * 
     * INTERNAL USE ONLY: Called by the backend scheduler, not via API.
     * Users should use the interactive periodic index for "trigger now".
     */
    suspend fun start(
        baseUrl: String,
        maxUrlCount: Int,
        sitemapUrl: String? = null,
        languagePattern: String? = null,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
        userId: UserId
    ): BatchPeriodicIndexJob

    /**
     * Stop a running batch job.
     * Users can cancel long-running batch jobs if needed.
     */
    suspend fun stop(jobId: Long)

    /**
     * Find a batch job by ID.
     * Used for polling to check progress.
     */
    suspend fun findById(jobId: Long): BatchPeriodicIndexJob?

    /**
     * List all batch jobs for a user.
     * Used for polling to check on recurring periodic index job status.
     */
    suspend fun listByUserId(userId: UserId): List<BatchPeriodicIndexJob>

    /**
     * List all batch jobs, optionally filtered by state.
     * Used internally by the scheduler to find resumable jobs.
     */
    suspend fun list(state: BatchPeriodicIndexJobState? = null): List<BatchPeriodicIndexJob>

    /**
     * Get summary statistics for a batch job.
     * Provides detailed progress for each stage.
     */
    suspend fun getStats(jobId: Long): BatchJobStats?
}

/**
 * Statistics for a batch periodic index job (4 stages).
 */
data class BatchJobStats(
    val jobId: Long,
    val state: BatchPeriodicIndexJobState,
    val stage: Int,
    val stageDescription: String,
    val totalUrls: Int,
    /** Stage 1: URLs that have been crawled + browser extracted */
    val processedUrls: Int,
    /** Stage 2: URLs with content LLM processing complete */
    val contentProcessedUrls: Int,
    /** Stage 3: URLs with final LLM processing complete */
    val finalProcessedUrls: Int,
    /** Stage 4: URLs written to cache */
    val cachedUrls: Int,
    val failedUrls: Int,
    val estimatedCompletionTimeMs: Long?
)

/**
 * Service for managing batch periodic index job lifecycle.
 * 
 * Batch jobs use Gemini's Batch API for cost-effective background processing.
 * Unlike interactive periodic index jobs, batch jobs can take up to 24+ hours
 * but provide 50% cost savings.
 */
@OptIn(ExperimentalTime::class)
class BatchPeriodicIndexJobService(
    private val normalizeUrlService: INormalizeUrlService,
    private val jobRepository: IBatchPeriodicIndexJobRepository,
    private val urlStateRepository: IBatchUrlStateRepository,
    private val orchestrator: IBatchPeriodicIndexOrchestrator
) : IBatchPeriodicIndexJobService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun start(
        baseUrl: String,
        maxUrlCount: Int,
        sitemapUrl: String?,
        languagePattern: String?,
        ocrLanguage: OcrLanguage,
        userId: UserId
    ): BatchPeriodicIndexJob {
        val normalizedBase = normalizeUrlService.normalize(baseUrl) ?: baseUrl
        val now = Clock.System.now()
        
        logger.info("Starting batch periodic index job for {} (max {} URLs)", normalizedBase, maxUrlCount)
        
        val job = BatchPeriodicIndexJob(
            id = null,
            userId = userId,
            baseUrl = normalizedBase,
            maxUrlCount = maxUrlCount,
            sitemapUrl = sitemapUrl,
            createdAt = now,
            updatedAt = now,
            state = BatchPeriodicIndexJobState.CRAWL_AND_EXTRACT,
            languagePattern = languagePattern,
            ocrLanguage = ocrLanguage
        )
        
        val created = jobRepository.create(job)
        
        // Start the orchestrator
        orchestrator.startOrResume(created)
        
        return created
    }

    override suspend fun stop(jobId: Long) {
        logger.info("Stopping batch periodic index job {}", jobId)
        orchestrator.stop(jobId)
    }

    override suspend fun findById(jobId: Long): BatchPeriodicIndexJob? {
        return jobRepository.findById(jobId)
    }

    override suspend fun listByUserId(userId: UserId): List<BatchPeriodicIndexJob> {
        return jobRepository.findByUserId(userId)
    }

    override suspend fun list(state: BatchPeriodicIndexJobState?): List<BatchPeriodicIndexJob> {
        return jobRepository.listAll(state)
    }

    override suspend fun getStats(jobId: Long): BatchJobStats? {
        val job = jobRepository.findById(jobId) ?: return null
        val counts = urlStateRepository.countByStage(jobId)
        
        // Estimate completion time based on current progress
        val estimatedCompletionTimeMs = if (job.isWaitingForBatch()) {
            // Estimate based on batch job creation time (target 24 hours)
            job.batchJobCreatedAt?.let { createdAt ->
                val elapsedMs = Clock.System.now().toEpochMilliseconds() - createdAt.toEpochMilliseconds()
                val remainingMs = (24 * 60 * 60 * 1000L) - elapsedMs
                if (remainingMs > 0) remainingMs else null
            }
        } else {
            null
        }
        
        return BatchJobStats(
            jobId = jobId,
            state = job.state,
            stage = job.currentStage(),
            stageDescription = job.stageDescription(),
            totalUrls = counts.total,
            processedUrls = counts.extracted,
            contentProcessedUrls = counts.contentLlmDone,
            finalProcessedUrls = counts.finalLlmDone,
            cachedUrls = counts.cached,
            failedUrls = counts.failed,
            estimatedCompletionTimeMs = estimatedCompletionTimeMs
        )
    }
}
