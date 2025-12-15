package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IQuerySessionService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.presentation.dto.DomainStatDto
import io.deepsearch.presentation.dto.ImageDto
import io.deepsearch.presentation.dto.QuerySessionAnalyticsDto
import io.deepsearch.presentation.dto.QuerySessionListResponse
import io.deepsearch.presentation.dto.toDetailDto
import io.deepsearch.presentation.dto.toSummaryDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import kotlin.io.encoding.Base64
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class QuerySessionController(
    private val querySessionService: IQuerySessionService,
    private val urlAccessService: IUrlAccessService,
    private val webpageImageRepository: IWebpageImageRepository
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    suspend fun getQuerySessions(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20
        val search = call.request.queryParameters["search"]?.takeIf { it.isNotBlank() }
        val domain = call.request.queryParameters["domain"]?.takeIf { it.isNotBlank() }
        val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
        val sortBy = call.request.queryParameters["sortBy"] ?: "createdAt"
        val sortOrder = call.request.queryParameters["sortOrder"] ?: "desc"

        if (page < 1 || pageSize < 1 || pageSize > 100) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid pagination parameters"))
            return
        }

        val offset = (page - 1) * pageSize
        
        // Use filtered query if any filters are present
        val hasFilters = search != null || domain != null || status != null
        
        val sessions = if (hasFilters || sortBy != "createdAt" || sortOrder != "desc") {
            querySessionService.getSessionsWithFilters(
                userId, search, domain, status, sortBy, sortOrder, offset, pageSize
            )
        } else {
            querySessionService.getSessionsByUserId(userId, offset, pageSize)
        }
        
        val totalCount = if (hasFilters) {
            querySessionService.countSessionsWithFilters(userId, search, domain, status).toInt()
        } else {
            querySessionService.countSessionsByUserId(userId).toInt()
        }

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
    
    suspend fun getQuerySessionAnalytics(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            return
        }

        val analytics = querySessionService.getAnalytics(userId)
        
        val response = QuerySessionAnalyticsDto(
            totalSessions = analytics.totalSessions,
            avgLiveSearchTimeMs = analytics.avgLiveSearchTimeMs,
            avgStaticSearchTimeMs = analytics.avgStaticSearchTimeMs,
            successRate = analytics.successRate,
            answerFoundRate = analytics.answerFoundRate,
            avgUrlsPerSession = analytics.avgUrlsPerSession,
            domainStats = analytics.domainStats.map { stat ->
                DomainStatDto(
                    domain = stat.domain,
                    sessionCount = stat.sessionCount,
                    avgSearchTimeMs = stat.avgSearchTimeMs,
                    successRate = stat.successRate,
                    answerFoundRate = stat.answerFoundRate
                )
            }
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
        
        // Fetch images if there are any referenced in the answer
        val images = if (sessionDetail.imageIds.isNotEmpty()) {
            fetchImagesByIds(sessionDetail.imageIds)
        } else {
            emptyMap()
        }
        
        call.respond(
            HttpStatusCode.OK,
            sessionDetail.session.toDetailDto(sessionDetail.urlAccesses, sessionDetail.cachedWebpages, images)
        )
    }

    /**
     * Fetch images by their IDs (format: "img-{urlSafeBase64Hash}") and convert to ImageDto map.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun fetchImagesByIds(imageIds: List<String>): Map<String, ImageDto> {
        if (imageIds.isEmpty()) return emptyMap()

        // Convert image IDs back to byte array hashes
        val idToHash = imageIds.mapNotNull { imageId ->
            if (imageId.startsWith("img-")) {
                try {
                    // Reverse URL-safe encoding: - -> +, _ -> /
                    val base64Hash = imageId.removePrefix("img-")
                        .replace("-", "+")
                        .replace("_", "/")
                    // Add padding if needed
                    val paddedHash = when (base64Hash.length % 4) {
                        2 -> "$base64Hash=="
                        3 -> "$base64Hash="
                        else -> base64Hash
                    }
                    imageId to Base64.decode(paddedHash)
                } catch (e: Exception) {
                    logger.warn("Failed to decode image ID {}: {}", imageId, e.message)
                    null
                }
            } else null
        }.toMap()

        if (idToHash.isEmpty()) return emptyMap()

        // Fetch all images by their hashes
        val hashes = idToHash.values.toList()
        val images = webpageImageRepository.findByHashes(hashes)

        // Build result map
        val result = mutableMapOf<String, ImageDto>()
        images.forEach { image ->
            // Find the image ID for this hash
            idToHash.entries.find { it.value.contentEquals(image.imageBytesHash) }?.let { (imageId, _) ->
                result[imageId] = ImageDto(
                    base64 = Base64.encode(image.imageBytes),
                    mimeType = image.mimeType
                )
            }
        }

        logger.debug("Fetched {} images out of {} requested", result.size, imageIds.size)
        return result
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}

