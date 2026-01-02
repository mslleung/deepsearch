package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IBatchPeriodicIndexJobService
import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.ExperimentalTime

/**
 * Controller for batch periodic index job endpoints.
 * 
 * Batch jobs are created internally by the backend scheduler for recurring
 * periodic index jobs. Users cannot trigger batch jobs directly - they use
 * the interactive method for "trigger now" which provides instant feedback.
 * 
 * These endpoints are READ-ONLY (plus stop) for viewing batch job progress via polling.
 */
@OptIn(ExperimentalTime::class)
class BatchPeriodicIndexController(
    private val batchJobService: IBatchPeriodicIndexJobService,
    private val apiKeyService: IApiKeyService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Response DTOs ==========

    @Serializable
    data class BatchJobResponse(
        val id: Long,
        val baseUrl: String,
        val maxUrlCount: Int,
        val sitemapUrl: String?,
        val languagePattern: String?,
        val ocrLanguage: String,
        val state: String,
        val stage: Int,
        val stageDescription: String,
        /** Stage 1: URLs that have been crawled + browser extracted */
        val urlsProcessed: Int,
        /** Stage 2: URLs with content LLM processing complete */
        val urlsContentProcessed: Int,
        /** Stage 3: URLs with final LLM processing complete */
        val urlsFinalProcessed: Int,
        /** Stage 4: URLs written to cache */
        val urlsCached: Int,
        /** Active batch job IDs for the current stage */
        val batchJobIds: List<String>,
        val errorMessage: String?,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        /** True if this is a batch job (slow but cheap), false for interactive */
        val isBatchJob: Boolean = true
    )

    @Serializable
    data class BatchJobStatsResponse(
        val jobId: Long,
        val state: String,
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

    // ========== Endpoints ==========

    /**
     * List all batch jobs for the authenticated user.
     * Used for polling to check on recurring periodic index job status.
     */
    suspend fun list(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let { 
            runCatching { BatchPeriodicIndexJobState.valueOf(it) }.getOrNull() 
        }
        
        val jobs = if (state != null) {
            batchJobService.list(state).filter { it.userId == userId }
        } else {
            batchJobService.listByUserId(userId)
        }
        
        call.respond(jobs.map { it.toResponse() })
    }

    /**
     * Get a batch job by ID.
     * Used for polling to check progress on a specific job.
     */
    suspend fun getById(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchJobService.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        // Check ownership
        if (job.userId != userId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return
        }

        call.respond(job.toResponse())
    }

    /**
     * Get statistics for a batch job.
     * Provides detailed progress for each stage.
     */
    suspend fun getStats(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchJobService.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        // Check ownership
        if (job.userId != userId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return
        }

        val stats = batchJobService.getStats(jobId)
        if (stats == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Stats not found"))
            return
        }

        call.respond(BatchJobStatsResponse(
            jobId = stats.jobId,
            state = stats.state.name,
            stage = stats.stage,
            stageDescription = stats.stageDescription,
            totalUrls = stats.totalUrls,
            processedUrls = stats.processedUrls,
            contentProcessedUrls = stats.contentProcessedUrls,
            finalProcessedUrls = stats.finalProcessedUrls,
            cachedUrls = stats.cachedUrls,
            failedUrls = stats.failedUrls,
            estimatedCompletionTimeMs = stats.estimatedCompletionTimeMs
        ))
    }

    /**
     * Stop a running batch job.
     * Users can cancel long-running batch jobs if needed.
     */
    suspend fun stop(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchJobService.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        // Check ownership
        if (job.userId != userId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return
        }

        batchJobService.stop(jobId)
        call.respond(HttpStatusCode.NoContent)
    }

    // ========== Helpers ==========

    private fun io.deepsearch.domain.models.entities.BatchPeriodicIndexJob.toResponse() = BatchJobResponse(
        id = this.id!!,
        baseUrl = this.baseUrl,
        maxUrlCount = this.maxUrlCount,
        sitemapUrl = this.sitemapUrl,
        languagePattern = this.languagePattern,
        ocrLanguage = this.ocrLanguage.code,
        state = this.state.name,
        stage = this.currentStage(),
        stageDescription = this.stageDescription(),
        urlsProcessed = this.urlsProcessed,
        urlsContentProcessed = this.urlsContentProcessed,
        urlsFinalProcessed = this.urlsFinalProcessed,
        urlsCached = this.urlsCached,
        batchJobIds = this.batchJobIds,
        errorMessage = this.errorMessage,
        createdAtMs = this.createdAt.toEpochMilliseconds(),
        updatedAtMs = this.updatedAt.toEpochMilliseconds(),
        isBatchJob = true
    )

    /**
     * Extract user ID from API key in bearer token.
     * Returns null if API key is missing or invalid.
     */
    private suspend fun getUserIdFromApiKey(call: ApplicationCall): UserId? {
        val principal = call.principal<UserIdPrincipal>()
        val rawApiKey = principal?.name ?: return null

        val isValid = apiKeyService.validateApiKey(rawApiKey)
        if (!isValid) {
            return null
        }

        val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey) ?: return null
        return apiKey.userId
    }
}
