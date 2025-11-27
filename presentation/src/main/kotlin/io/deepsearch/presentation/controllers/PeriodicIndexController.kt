package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IPeriodicIndexService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.entities.PeriodicIndexPeriod
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.UrlAccess
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PeriodicIndexController(
    private val periodicIndexService: IPeriodicIndexService,
    private val urlAccessService: IUrlAccessService,
    private val webpageMarkdownRepository: IWebpageMarkdownRepository
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun getConfig(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val config = periodicIndexService.getConfig(userId)
        if (config == null) {
            // Return empty/default response or 404? Let's return 404 or null content.
            // Better to return 204 No Content or 404. 
            // For frontend simplicity, let's return 204.
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.OK, config.toResponse())
        }
    }

    suspend fun saveConfig(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val request = try {
            call.receive<PeriodicIndexConfigRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            return
        }

        if (request.url.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "URL is required"))
            return
        }

        if (!PeriodicIndexPeriod.isValidPeriodDays(request.periodDays)) {
            val allowedValues = PeriodicIndexPeriod.ALLOWED_DAYS.filterNotNull().sorted().joinToString(", ")
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid indexing period. Allowed values are: $allowedValues days (or null for one-off)")
            )
            return
        }

        if (request.maxUrlCount !in PeriodicIndexConfig.MIN_MAX_URL_COUNT..PeriodicIndexConfig.MAX_MAX_URL_COUNT) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Max URL count must be between ${PeriodicIndexConfig.MIN_MAX_URL_COUNT} and ${PeriodicIndexConfig.MAX_MAX_URL_COUNT}")
            )
            return
        }

        val config = periodicIndexService.createOrUpdateConfig(userId, request.url, request.sitemapUrl, request.periodDays, request.maxUrlCount)
        call.respond(HttpStatusCode.OK, config.toResponse())
    }

    suspend fun deleteConfig(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        periodicIndexService.deleteConfig(userId)
        call.respond(HttpStatusCode.NoContent)
    }

    suspend fun triggerNow(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        try {
            val job = periodicIndexService.triggerNow(userId)
            call.respond(HttpStatusCode.OK, job.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Failed to trigger periodic index for user $userId", e)
            call.respond(HttpStatusCode.InternalServerError, "Failed to trigger index")
        }
    }

    suspend fun getJobHistory(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 10

        val (jobs, total) = periodicIndexService.listJobHistory(userId, page, pageSize)
        
        call.respond(HttpStatusCode.OK, PeriodicIndexJobHistoryResponse(
            jobs = jobs.map { it.toResponse() },
            totalCount = total
        ))
    }

    suspend fun getJobUrls(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val jobIdParam = call.parameters["jobId"]
        if (jobIdParam == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Job ID required"))
            return
        }

        val jobId = jobIdParam.toLongOrNull()
        if (jobId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid job ID"))
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 50

        // Create session ID for the periodic index job
        val sessionId = PeriodicIndexSessionId(jobId)

        val (urls, total) = urlAccessService.getUrlAccessesBySession(sessionId, page, pageSize)
        
        // Build the response by looking up page titles from WebpageMarkdown
        val urlsWithTitles = buildUrlResponsesWithTitles(urls, page, pageSize)

        call.respond(HttpStatusCode.OK, PeriodicIndexJobUrlListResponse(
            urls = urlsWithTitles,
            totalCount = total
        ))
    }

    suspend fun getIndexedUrlsByBaseUrl(call: ApplicationCall) {
        val userId = getUserIdFromJwt(call)
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val baseUrl = call.request.queryParameters["baseUrl"]
        if (baseUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Base URL required"))
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 50

        val (urls, total) = urlAccessService.getUrlAccessesByBaseUrl(baseUrl, page, pageSize)
        
        // Build the response by looking up page titles from WebpageMarkdown
        val urlsWithTitles = buildUrlResponsesWithTitles(urls, page, pageSize)

        call.respond(HttpStatusCode.OK, PeriodicIndexJobUrlListResponse(
            urls = urlsWithTitles,
            totalCount = total
        ))
    }

    /**
     * Build URL responses with page titles fetched from WebpageMarkdown.
     * Similar pattern to QuerySessionService.getSessionDetail.
     */
    private suspend fun buildUrlResponsesWithTitles(
        urls: List<UrlAccess>,
        page: Int,
        pageSize: Int
    ): List<PeriodicIndexJobUrlResponse> {
        // Fetch page titles from WebpageMarkdown for each URL
        val urlToTitle: Map<String, String?> = urls.associate { urlAccess ->
            val webpage = webpageMarkdownRepository.findByUrl(urlAccess.url)
            urlAccess.url to webpage?.title
        }

        return urls.mapIndexed { index, urlAccess ->
            val pageTitle = urlToTitle[urlAccess.url]
            urlAccess.toPeriodicIndexJobUrlResponse(
                id = (page - 1) * pageSize + index + 1L,
                pageTitle = pageTitle
            )
        }
    }

    private fun getUserIdFromJwt(call: ApplicationCall): UserId? {
        val principal = call.principal<JWTPrincipal>()
        val userIdClaim = principal?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asInt()
        return userIdClaim?.let { UserId(it) }
    }
}

