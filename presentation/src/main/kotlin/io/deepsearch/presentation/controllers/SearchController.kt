package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.IRateLimitService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.domain.exceptions.AiInterpretationException
import io.deepsearch.domain.exceptions.InvalidUrlException
import io.deepsearch.domain.exceptions.WebScrapeException
import io.deepsearch.domain.exceptions.WebScrapeTimeoutException
import io.deepsearch.presentation.dto.SearchRequest
import io.deepsearch.presentation.dto.toResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
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
                        "limit" to apiKey.rateLimitPerMinute,
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
            
            // Record the request
            rateLimitService.recordUsage(apiKey.id!!)
            
            val request = call.receive<SearchRequest>()
            val searchResult = searchService.searchWebsite(request.query, request.url)
            
            // Consume usage after successful search
            subscriptionPlanService.consumeUsage(apiKey.userId)
            
            call.respond(HttpStatusCode.OK, searchResult.toResponse())
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
} 