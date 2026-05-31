package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.ICostCalculationService
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.deepsearch.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminPeriodicIndexJobController(
    private val periodicIndexJobRepository: IPeriodicIndexJobRepository,
    private val costCalculationService: ICostCalculationService
) {

    suspend fun getAllPeriodicIndexJobs(call: ApplicationCall) {
        val stateParam = call.request.queryParameters["state"]
        val state = stateParam?.let {
            runCatching { PeriodicIndexJobState.valueOf(it.uppercase()) }.getOrNull()
        }

        val jobs = periodicIndexJobRepository.listAll(state)
        val jobsDto = jobs.map { it.toAdminDto() }

        call.respond(HttpStatusCode.OK, jobsDto)
    }

    suspend fun getPeriodicIndexJobById(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = periodicIndexJobRepository.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        call.respond(HttpStatusCode.OK, job.toAdminDto())
    }

    suspend fun getPeriodicIndexJobCost(call: ApplicationCall) {
        val jobId = call.parameters["id"]?.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val job = periodicIndexJobRepository.findById(jobId)
        if (job == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            return
        }

        val sessionId = PeriodicIndexSessionId(jobId)
        val costSummary = costCalculationService.calculateSessionCost(sessionId)

        call.respond(HttpStatusCode.OK, costSummary.toDto())
    }
}
