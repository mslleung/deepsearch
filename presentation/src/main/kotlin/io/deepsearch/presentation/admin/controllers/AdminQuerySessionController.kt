package io.deepsearch.presentation.admin.controllers

import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.presentation.admin.dto.toAdminDetailDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class AdminQuerySessionController(
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService
) {

    suspend fun getQuerySessionById(call: ApplicationCall) {
        val sessionIdParam = call.parameters["id"]
        if (sessionIdParam == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Session ID required"))
            return
        }
        val sessionId = QuerySessionId(sessionIdParam)

        val session = querySessionService.getSession(sessionId)

        // Query URL accesses separately
        val urlAccesses = urlAccessService.getUrlAccessesBySession(sessionId)
        
        call.respond(HttpStatusCode.OK, session.toAdminDetailDto(urlAccesses))
    }
}

