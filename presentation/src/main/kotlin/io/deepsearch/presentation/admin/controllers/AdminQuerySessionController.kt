package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.domain.repositories.IQuerySessionRepository
import io.deepsearch.presentation.admin.dto.toAdminDetailDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminQuerySessionController(
    private val querySessionRepository: IQuerySessionRepository,
    private val urlAccessService: IUrlAccessService
) {

    suspend fun getQuerySessionById(call: ApplicationCall) {
        try {
            val sessionId = call.parameters["id"]
            if (sessionId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Session ID required"))
                return
            }

            val session = querySessionRepository.findById(sessionId)
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
                return
            }

            // Query URL accesses separately
            val urlAccesses = urlAccessService.getUrlAccessesBySession(sessionId)
            
            call.respond(HttpStatusCode.OK, session.toAdminDetailDto(urlAccesses))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}

