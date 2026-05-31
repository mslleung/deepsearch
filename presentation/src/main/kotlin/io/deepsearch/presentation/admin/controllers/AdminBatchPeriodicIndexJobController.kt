package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.ICostCalculationService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.BatchPeriodicIndexSessionId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.presentation.admin.dto.AdminBatchJobStatsDto
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.deepsearch.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AdminBatchPeriodicIndexJobController(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val urlStateRepository: IBatchUrlStateRepository,
    private val costCalculationService: ICostCalculationService
) {

    suspend fun getAllBatchJobs(call: ApplicationCall) {
        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let {
            runCatching { BatchPeriodicIndexJobState.valueOf(it.uppercase()) }.getOrNull()
        }

        val jobs = batchJobRepository.listAll(state)
        val jobsDto = jobs.map { it.toAdminDto() }

        call.respond(HttpStatusCode.OK, jobsDto)
    }

    suspend fun getBatchJobById(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchJobRepository.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        call.respond(HttpStatusCode.OK, job.toAdminDto())
    }

    suspend fun getBatchJobStats(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchJobRepository.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        val counts = urlStateRepository.countByStage(jobId)

        val estimatedCompletionTimeMs = if (job.isWaitingForBatch()) {
            job.batchJobCreatedAt?.let { createdAt ->
                val elapsedMs = Clock.System.now().toEpochMilliseconds() - createdAt.toEpochMilliseconds()
                val remainingMs = (24 * 60 * 60 * 1000L) - elapsedMs
                if (remainingMs > 0) remainingMs else null
            }
        } else {
            null
        }

        val statsDto = AdminBatchJobStatsDto(
            jobId = jobId,
            state = job.state.name,
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

        call.respond(HttpStatusCode.OK, statsDto)
    }

    suspend fun getBatchJobCost(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = batchJobRepository.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        val sessionId = BatchPeriodicIndexSessionId(jobId)
        val costSummary = costCalculationService.calculateSessionCost(sessionId)

        call.respond(HttpStatusCode.OK, costSummary.toDto())
    }
}
