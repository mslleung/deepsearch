package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IPeriodicIndexJobService
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminPeriodicIndexJobController(
    private val periodicIndexJobService: IPeriodicIndexJobService
) {

    suspend fun getAllPeriodicIndexJobs(call: ApplicationCall) {
        try {
            val stateParam = call.request.queryParameters["state"]
            val state = stateParam?.let { 
                try {
                    PeriodicIndexJobState.valueOf(it.uppercase())
                } catch (e: Exception) {
                    null
                }
            }
            
            val jobs = periodicIndexJobService.list(state)
            val jobsDto = jobs.map { it.toAdminDto() }
            
            call.respond(HttpStatusCode.OK, jobsDto)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getPeriodicIndexJobById(call: ApplicationCall) {
        try {
            val jobId = call.parameters["id"]?.toLongOrNull()
            if (jobId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
                return
            }

            val job = periodicIndexJobService.findById(jobId)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
                return
            }

            call.respond(HttpStatusCode.OK, job.toAdminDto())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun stopPeriodicIndexJob(call: ApplicationCall) {
        try {
            val jobId = call.parameters["id"]?.toLongOrNull()
            if (jobId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
                return
            }

            val job = periodicIndexJobService.findById(jobId)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
                return
            }

            periodicIndexJobService.stop(jobId)
            
            // Fetch the updated job to return
            val updatedJob = periodicIndexJobService.findById(jobId)
            if (updatedJob != null) {
                call.respond(HttpStatusCode.OK, updatedJob.toAdminDto())
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}
