package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.presentation.dto.QuerySessionListResponse
import io.deepsearch.presentation.dto.toDetailDto
import io.deepsearch.presentation.dto.toSummaryDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

class QuerySessionController(
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService
) {
    suspend fun getQuerySessions(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20

        if (page < 1 || pageSize < 1 || pageSize > 100) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid pagination parameters"))
            return
        }

        val offset = (page - 1) * pageSize
        val sessions = querySessionService.getSessionsByUserId(userId, offset, pageSize)
        val totalCount = querySessionService.countSessionsByUserId(userId).toInt()

        // Get URL count for each session
        val sessionsWithUrlCount = sessions.map { session ->
            val urlCount = urlAccessService.getUrlAccessesBySession(session.id).size
            session.toSummaryDto(urlCount)
        }

        val response = QuerySessionListResponse(
            sessions = sessionsWithUrlCount,
            page = page,
            pageSize = pageSize,
            totalCount = totalCount
        )

        call.respond(HttpStatusCode.OK, response)
    }

    suspend fun getQuerySessionDetail(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val sessionIdParam = call.parameters["id"]
        if (sessionIdParam == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Session ID required"))
            return
        }
        val sessionId = QuerySessionId(sessionIdParam)

        val sessionDetail = querySessionService.getSessionDetail(sessionId, userId)
        
        call.respond(
            HttpStatusCode.OK,
            sessionDetail.session.toDetailDto(sessionDetail.urlAccesses, sessionDetail.cachedWebpages)
        )
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}

