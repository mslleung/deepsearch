package io.deepsearch.presentation.admin.controllers

import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import io.deepsearch.presentation.admin.dto.toAdminDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminPrecacheController(
    private val precacheJobRepository: IPrecacheJobRepository
) {

    suspend fun getAllPrecacheJobs(call: ApplicationCall) {
        try {
            val stateParam = call.request.queryParameters["state"]
            val state = stateParam?.let { 
                try {
                    PrecacheJobState.valueOf(it.uppercase())
                } catch (e: Exception) {
                    null
                }
            }
            
            val jobs = precacheJobRepository.listAll(state)
            val jobsDto = jobs.map { it.toAdminDto() }
            
            call.respond(HttpStatusCode.OK, jobsDto)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun getPrecacheJobById(call: ApplicationCall) {
        try {
            val jobId = call.parameters["id"]?.toLongOrNull()
            if (jobId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
                return
            }

            val job = precacheJobRepository.findById(jobId)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
                return
            }

            call.respond(HttpStatusCode.OK, job.toAdminDto())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    suspend fun stopPrecacheJob(call: ApplicationCall) {
        try {
            val jobId = call.parameters["id"]?.toLongOrNull()
            if (jobId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
                return
            }

            val job = precacheJobRepository.findById(jobId)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
                return
            }

            job.markStopped()
            val updatedJob = precacheJobRepository.update(job)
            
            call.respond(HttpStatusCode.OK, updatedJob.toAdminDto())
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}

