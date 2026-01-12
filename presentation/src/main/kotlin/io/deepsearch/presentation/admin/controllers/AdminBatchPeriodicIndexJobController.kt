package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IBatchPeriodicIndexJobService
import io.deepsearch.application.services.ICostCalculationService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.BatchPeriodicIndexSessionId
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.deepsearch.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Admin controller for batch periodic index jobs.
 * Provides endpoints for listing, viewing, and managing batch jobs.
 */
class AdminBatchPeriodicIndexJobController(
    private val batchPeriodicIndexJobService: IBatchPeriodicIndexJobService,
    private val costCalculationService: ICostCalculationService
) {

    /**
     * GET /admin/batch-periodic-index-jobs
     * List all batch periodic index jobs, optionally filtered by state.
     */
    suspend fun getAllBatchJobs(call: ApplicationCall) {
        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let { 
            runCatching { BatchPeriodicIndexJobState.valueOf(it.uppercase()) }.getOrNull()
        }
        
        val jobs = batchPeriodicIndexJobService.list(state)
        val jobsDto = jobs.map { it.toAdminDto() }
        
        call.respond(HttpStatusCode.OK, jobsDto)
    }

    /**
     * GET /admin/batch-periodic-index-jobs/{id}
     * Get a specific batch job by ID.
     */
    suspend fun getBatchJobById(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchPeriodicIndexJobService.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        call.respond(HttpStatusCode.OK, job.toAdminDto())
    }

    /**
     * GET /admin/batch-periodic-index-jobs/{id}/stats
     * Get detailed statistics for a batch job.
     */
    suspend fun getBatchJobStats(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val stats = batchPeriodicIndexJobService.getStats(jobId)
        if (stats == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        call.respond(HttpStatusCode.OK, stats.toAdminDto())
    }

    /**
     * GET /admin/batch-periodic-index-jobs/{id}/cost
     * Get cost breakdown for a batch job.
     */
    suspend fun getBatchJobCost(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchPeriodicIndexJobService.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        val sessionId = BatchPeriodicIndexSessionId(jobId)
        val costSummary = costCalculationService.calculateSessionCost(sessionId)
        
        call.respond(HttpStatusCode.OK, costSummary.toDto())
    }

    /**
     * POST /admin/batch-periodic-index-jobs/{id}/stop
     * Stop a running batch job.
     */
    suspend fun stopBatchJob(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchPeriodicIndexJobService.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        batchPeriodicIndexJobService.stop(jobId)
        
        // Fetch the updated job to return
        val updatedJob = batchPeriodicIndexJobService.findById(jobId)
        if (updatedJob != null) {
            call.respond(HttpStatusCode.OK, updatedJob.toAdminDto())
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
