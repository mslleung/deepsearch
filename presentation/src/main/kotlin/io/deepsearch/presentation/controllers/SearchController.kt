package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IRateLimitService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.exceptions.AiInterpretationException
import io.deepsearch.domain.exceptions.InvalidUrlException
import io.deepsearch.domain.exceptions.WebScrapeException
import io.deepsearch.domain.exceptions.WebScrapeTimeoutException
import io.deepsearch.presentation.dto.SearchRequest
import io.deepsearch.presentation.dto.toDetailDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SearchController(
    private val searchService: ISearchService,
    private val apiKeyService: IApiKeyService,
    private val rateLimitService: IRateLimitService,
    private val subscriptionPlanService: IUserSubscriptionService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun searchWebsite(call: ApplicationCall) {
        try {
            // Get API key from bearer token
            val principal = call.principal<UserIdPrincipal>()
            val rawApiKey = principal?.name
            
            if (rawApiKey == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "API key required"))
                return
            }
            
            // Validate API key
            val isApikeyOk = apiKeyService.validateApiKey(rawApiKey)
            if (!isApikeyOk) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
                return
            }

            val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey)!!

            // Check rate limit
            val allowed = rateLimitService.checkRateLimit(apiKey.id!!, apiKey.rateLimitPerMinute)
            if (!allowed) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf(
                        "error" to "Rate limit exceeded",
                        "limit" to apiKey.rateLimitPerMinute.toString(),
                        "message" to "You have exceeded ${apiKey.rateLimitPerMinute} requests per minute"
                    )
                )
                return
            }
            
            // Check usage limit (subscription quota)
            val hasUsageRemaining = subscriptionPlanService.checkUsageLimit(apiKey.userId)
            if (!hasUsageRemaining) {
                call.respond(
                    HttpStatusCode.PaymentRequired,
                    mapOf(
                        "error" to "Usage limit exceeded",
                        "message" to "You have reached your plan's search limit. Please upgrade your plan."
                    )
                )
                return
            }

            apiKeyService.incrementApiKeyUsage(rawApiKey)
            
            val request = call.receive<SearchRequest>()
            
            // Validate cache expiry parameter
            request.maxCacheAge?.let { maxCacheAge ->
                if (maxCacheAge <= 0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "maxCacheAge must be positive. Received: $maxCacheAge")
                    )
                    return
                }
            }
            
            // Parse and validate mode
            val searchMode = try {
                request.toSearchMode()
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to e.message)
                )
                return
            }
            
            // Execute search and get session detail
            val sessionDetail = searchService.searchWebsite(
                request.query, 
                request.url, 
                request.maxCacheAge,
                searchMode,
                apiKey.id!!,
                apiKey.userId
            )
            
            // Consume usage after successful search
            subscriptionPlanService.consumeUsage(apiKey.userId)
            
            // Convert to DTO and respond
            val responseDto = sessionDetail.session.toDetailDto(
                sessionDetail.urlAccesses,
                sessionDetail.cachedWebpages
            )
            call.respond(HttpStatusCode.OK, responseDto)
        } catch (e: IllegalArgumentException) {
            logger.warn("Bad request in search: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: InvalidUrlException) {
            logger.warn("Invalid URL in search: {}", e.message, e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: WebScrapeTimeoutException) {
            logger.warn("Web scrape timeout in search: {}", e.message, e)
            call.respond(HttpStatusCode.RequestTimeout, mapOf("error" to e.message))
        } catch (e: WebScrapeException) {
            logger.error("Web scrape error in search: {}", e.message, e)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to e.message))
        } catch (e: AiInterpretationException) {
            logger.error("AI interpretation error in search: {}", e.message, e)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error("Unexpected error in search: {}", e.message, e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    /**
     * Stream search events via SSE.
     * Validates auth, starts search, and streams events until completion.
     * Auth via query param apiKey since EventSource cannot set headers.
     */
    suspend fun streamSearch(call: ApplicationCall, sse: ServerSSESession) {
        try {
            // Validate API key from query param (EventSource cannot set headers)
            val rawApiKey = call.request.queryParameters["apiKey"]
            if (rawApiKey == null) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "Unauthorized", "Missing apiKey parameter"))))
                return
            }

            val isApikeyOk = apiKeyService.validateApiKey(rawApiKey)
            if (!isApikeyOk) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "Unauthorized", "Invalid API key"))))
                return
            }

            val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey)!!

            // Check rate limit
            val allowed = rateLimitService.checkRateLimit(apiKey.id!!, apiKey.rateLimitPerMinute)
            if (!allowed) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "RateLimitExceeded", 
                        "You have exceeded ${apiKey.rateLimitPerMinute} requests per minute"))))
                return
            }

            // Check usage limit
            val hasUsageRemaining = subscriptionPlanService.checkUsageLimit(apiKey.userId)
            if (!hasUsageRemaining) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "UsageLimitExceeded", 
                        "You have reached your plan's search limit. Please upgrade your plan."))))
                return
            }

            // Parse request params
            val query = call.request.queryParameters["query"]
            val url = call.request.queryParameters["url"]
            val maxCacheAge = call.request.queryParameters["maxCacheAge"]?.toLongOrNull()
            val modeParam = call.request.queryParameters["mode"]

            if (query.isNullOrBlank() || url.isNullOrBlank()) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "BadRequest", "Missing query or url parameter"))))
                return
            }

            // Validate cache expiry
            if (maxCacheAge != null && maxCacheAge <= 0) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "BadRequest", "maxCacheAge must be positive"))))
                return
            }

            // Parse mode
            val searchMode = try {
                SearchRequest(query, url, maxCacheAge, modeParam).toSearchMode()
            } catch (e: IllegalArgumentException) {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", "BadRequest", e.message ?: "Invalid mode"))))
                return
            }

            apiKeyService.incrementApiKeyUsage(rawApiKey)
            subscriptionPlanService.consumeUsage(apiKey.userId)

            // Execute streaming search and forward events
            val eventFlow = searchService.executeStreaming(
                query,
                url,
                maxCacheAge,
                searchMode,
                apiKey.id!!
            )

            eventFlow.collect { event ->
                val payload = Json.encodeToString(SearchEvent.serializer(), event)
                sse.send(ServerSentEvent(payload))
            }

        } catch (e: Exception) {
            logger.error("Error in streaming search: {}", e.message, e)
            try {
                sse.send(ServerSentEvent(Json.encodeToString(SearchEvent.serializer(), 
                    SearchEvent.SessionError("", e::class.simpleName ?: "Unknown", e.message ?: "Unknown error"))))
            } catch (sendError: Exception) {
                logger.warn("Failed to send error event: {}", sendError.message)
            }
        }
    }
}
