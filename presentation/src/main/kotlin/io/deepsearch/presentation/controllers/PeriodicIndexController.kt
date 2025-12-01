package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IPeriodicIndexService
import io.deepsearch.application.services.IUrlAccessService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.application.services.PeriodicIndexLimitExceededException
import io.deepsearch.domain.models.entities.PeriodicIndexConfig
import io.deepsearch.domain.models.entities.PeriodicIndexPeriod
import io.deepsearch.domain.models.entities.SubscriptionPlan
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.UrlAccess
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IWebpageMarkdownRepository
import io.deepsearch.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PeriodicIndexController(
    private val periodicIndexService: IPeriodicIndexService,
    private val urlAccessService: IUrlAccessService,
    private val webpageMarkdownRepository: IWebpageMarkdownRepository,
    private val userSubscriptionService: IUserSubscriptionService,
    private val apiKeyService: IApiKeyService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * GET /api/periodic-index/configs
     * List all periodic index configs for the user
     */
    suspend fun listConfigs(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configs = periodicIndexService.listConfigs(userId)
        val maxAllowed = getMaxAllowedConfigs(userId)
        
        call.respond(HttpStatusCode.OK, PeriodicIndexConfigListResponse(
            configs = configs.map { it.toResponse() },
            totalCount = configs.size,
            maxAllowed = maxAllowed
        ))
    }

    /**
     * GET /api/periodic-index/configs/{id}
     * Get a specific periodic index config
     */
    suspend fun getConfigById(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        val config = periodicIndexService.getConfig(configId)
        if (config == null || config.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        call.respond(HttpStatusCode.OK, config.toResponse())
    }

    /**
     * POST /api/periodic-index/configs
     * Create a new periodic index config
     */
    suspend fun createConfig(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val request = try {
            call.receive<PeriodicIndexConfigRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            return
        }

        val validationError = validateConfigRequest(request)
        if (validationError != null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
            return
        }

        val maxAllowed = getMaxAllowedConfigs(userId)
        
        try {
            val config = periodicIndexService.createConfig(
                userId = userId,
                url = request.url,
                sitemapUrl = request.sitemapUrl,
                periodDays = request.periodDays,
                maxUrlCount = request.maxUrlCount,
                maxAllowedConfigs = maxAllowed
            )
            call.respond(HttpStatusCode.Created, config.toResponse())
        } catch (e: PeriodicIndexLimitExceededException) {
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to "You have reached your limit of ${e.maxAllowed} periodic index configurations. Please upgrade your plan for more.",
                "currentCount" to e.currentCount,
                "maxAllowed" to e.maxAllowed
            ))
        } catch (e: Exception) {
            logger.error("Failed to create periodic index config for user $userId", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create config"))
        }
    }

    /**
     * PUT /api/periodic-index/configs/{id}
     * Update an existing periodic index config
     */
    suspend fun updateConfig(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        // Verify ownership
        val existingConfig = periodicIndexService.getConfig(configId)
        if (existingConfig == null || existingConfig.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        val request = try {
            call.receive<PeriodicIndexConfigRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            return
        }

        val validationError = validateConfigRequest(request)
        if (validationError != null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
            return
        }

        try {
            val config = periodicIndexService.updateConfig(
                configId = configId,
                url = request.url,
                sitemapUrl = request.sitemapUrl,
                periodDays = request.periodDays,
                maxUrlCount = request.maxUrlCount
            )
            call.respond(HttpStatusCode.OK, config.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Failed to update periodic index config $configId", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update config"))
        }
    }

    /**
     * DELETE /api/periodic-index/configs/{id}
     * Delete a periodic index config
     */
    suspend fun deleteConfig(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        // Verify ownership
        val existingConfig = periodicIndexService.getConfig(configId)
        if (existingConfig == null || existingConfig.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        periodicIndexService.deleteConfig(configId)
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * PUT /api/periodic-index/configs/{id}/enable
     * Enable a periodic index config
     */
    suspend fun enableConfig(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        // Verify ownership
        val existingConfig = periodicIndexService.getConfig(configId)
        if (existingConfig == null || existingConfig.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        try {
            val config = periodicIndexService.enableConfig(configId)
            call.respond(HttpStatusCode.OK, config.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Failed to enable periodic index config $configId", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to enable config"))
        }
    }

    /**
     * PUT /api/periodic-index/configs/{id}/disable
     * Disable a periodic index config
     */
    suspend fun disableConfig(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        // Verify ownership
        val existingConfig = periodicIndexService.getConfig(configId)
        if (existingConfig == null || existingConfig.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        try {
            val config = periodicIndexService.disableConfig(configId)
            call.respond(HttpStatusCode.OK, config.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Failed to disable periodic index config $configId", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to disable config"))
        }
    }

    /**
     * POST /api/periodic-index/configs/{id}/trigger
     * Trigger a periodic index job for a specific config
     */
    suspend fun triggerConfig(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        // Verify ownership
        val existingConfig = periodicIndexService.getConfig(configId)
        if (existingConfig == null || existingConfig.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        try {
            val job = periodicIndexService.triggerNow(configId)
            call.respond(HttpStatusCode.OK, job.toResponse())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            logger.error("Failed to trigger periodic index for config $configId", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to trigger index"))
        }
    }

    /**
     * GET /api/periodic-index/history
     * Get global job history for all configs
     */
    suspend fun getGlobalJobHistory(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 10

        val (jobs, total) = periodicIndexService.listJobHistory(userId, page, pageSize)
        
        call.respond(HttpStatusCode.OK, PeriodicIndexJobHistoryResponse(
            jobs = jobs.map { it.toResponse() },
            totalCount = total
        ))
    }

    /**
     * GET /api/periodic-index/configs/{id}/history
     * Get job history for a specific config
     */
    suspend fun getConfigJobHistory(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

        val configId = call.parameters["id"]?.toLongOrNull()
        if (configId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config ID"))
            return
        }

        // Verify ownership and get config to use its URL
        val config = periodicIndexService.getConfig(configId)
        if (config == null || config.userId != userId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
            return
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 10

        val (jobs, total) = periodicIndexService.listJobHistoryForConfig(userId, config.url, page, pageSize)
        
        call.respond(HttpStatusCode.OK, PeriodicIndexJobHistoryResponse(
            jobs = jobs.map { it.toResponse() },
            totalCount = total
        ))
    }

    /**
     * GET /api/periodic-index/history/{jobId}/urls
     * Get URLs processed in a specific job
     */
    suspend fun getJobUrls(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

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

    /**
     * GET /api/periodic-index/indexed-urls
     * Get indexed URLs by base URL
     */
    suspend fun getIndexedUrlsByBaseUrl(call: ApplicationCall) {
        val userId = getUserIdFromApiKey(call) ?: return call.respond(HttpStatusCode.Unauthorized)

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

    private fun validateConfigRequest(request: PeriodicIndexConfigRequest): String? {
        if (request.url.isBlank()) {
            return "URL is required"
        }

        if (!PeriodicIndexPeriod.isValidPeriodDays(request.periodDays)) {
            val allowedValues = PeriodicIndexPeriod.ALLOWED_DAYS.filterNotNull().sorted().joinToString(", ")
            return "Invalid indexing period. Allowed values are: $allowedValues days (or null for one-off)"
        }

        if (request.maxUrlCount !in PeriodicIndexConfig.MIN_MAX_URL_COUNT..PeriodicIndexConfig.MAX_MAX_URL_COUNT) {
            return "Max URL count must be between ${PeriodicIndexConfig.MIN_MAX_URL_COUNT} and ${PeriodicIndexConfig.MAX_MAX_URL_COUNT}"
        }

        return null
    }

    private suspend fun getMaxAllowedConfigs(userId: UserId): Int {
        val subscription = userSubscriptionService.getUsableUserSubscription(userId)
        val plan = subscription?.let { SubscriptionPlan.fromName(it.planName) } ?: SubscriptionPlan.FREE
        return plan.maxPeriodicIndexConfigs
    }

    private suspend fun buildUrlResponsesWithTitles(
        urls: List<UrlAccess>,
        page: Int,
        pageSize: Int
    ): List<PeriodicIndexJobUrlResponse> {
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

    /**
     * Extract user ID from API key in bearer token.
     * Returns null if API key is missing or invalid.
     */
    private suspend fun getUserIdFromApiKey(call: ApplicationCall): UserId? {
        val principal = call.principal<UserIdPrincipal>()
        val rawApiKey = principal?.name ?: return null

        val isValid = apiKeyService.validateApiKey(rawApiKey)
        if (!isValid) {
            return null
        }

        val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey) ?: return null
        return apiKey.userId
    }
}
