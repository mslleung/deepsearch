package io.deepsearch.presentation.config

import io.deepsearch.application.services.PeriodicIndexLimitExceededException
import io.deepsearch.domain.exceptions.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StatusPagesConfig")

/**
 * Configures StatusPages plugin for centralized exception handling.
 * Maps domain exceptions to appropriate HTTP status codes with consistent error response format.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // Domain exceptions - User-related
        exception<UserNotFoundException> { call, cause ->
            logger.warn("User not found: {}", cause.message)
            call.respond(HttpStatusCode.NotFound, mapOf("error" to cause.message))
        }

        exception<UserAlreadyExistsException> { call, cause ->
            logger.warn("User already exists: {}", cause.message)
            call.respond(HttpStatusCode.Conflict, mapOf("error" to cause.message))
        }

        exception<InvalidUserDataException> { call, cause ->
            logger.warn("Invalid user data: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
        }

        // Domain exceptions - URL/Web scraping
        exception<InvalidUrlException> { call, cause ->
            logger.warn("Invalid URL: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
        }

        exception<WebScrapeTimeoutException> { call, cause ->
            logger.warn("Web scrape timeout: {}", cause.message)
            call.respond(HttpStatusCode.GatewayTimeout, mapOf("error" to cause.message))
        }

        exception<WebScrapeException> { call, cause ->
            logger.error("Web scrape error: {}", cause.message, cause)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to cause.message))
        }

        // Domain exceptions - AI
        exception<AiInterpretationException> { call, cause ->
            logger.error("AI interpretation error: {}", cause.message, cause)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to cause.message))
        }

        // Domain exceptions - Concurrency
        exception<OptimisticLockException> { call, cause ->
            logger.warn("Optimistic lock conflict: {}", cause.message)
            call.respond(HttpStatusCode.Conflict, mapOf("error" to cause.message))
        }

        // URL Processing exceptions - Network errors
        exception<NetworkConnectionException> { call, cause ->
            logger.warn("Network connection error for {}: {}", cause.url, cause.reason)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to cause.reason))
        }

        // URL Processing exceptions - Markdown conversion errors
        exception<MarkdownConversionException> { call, cause ->
            logger.error("Markdown conversion error for {}: {}", cause.url, cause.reason, cause)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to cause.reason))
        }

        // LLM exceptions
        exception<LlmTimeoutException> { call, cause ->
            logger.warn("LLM timeout: {}", cause.message)
            call.respond(HttpStatusCode.GatewayTimeout, mapOf("error" to cause.message))
        }

        exception<LlmRateLimitException> { call, cause ->
            logger.warn("LLM rate limit exceeded: {}", cause.message)
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to cause.message))
        }

        exception<LlmDeserializationException> { call, cause ->
            logger.error("LLM deserialization error: {}", cause.message, cause)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to cause.message))
        }

        exception<LlmGenericException> { call, cause ->
            logger.error("LLM error: {}", cause.message, cause)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to cause.message))
        }

        // Application exceptions
        exception<PeriodicIndexLimitExceededException> { call, cause ->
            logger.warn("Periodic index limit exceeded: {}/{}", cause.currentCount, cause.maxAllowed)
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to cause.message))
        }

        // Ktor validation exceptions
        exception<RequestValidationException> { call, cause ->
            logger.warn("Request validation failed: {}", cause.reasons.joinToString())
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.reasons.joinToString("; ")))
        }

        // Common exceptions
        exception<IllegalArgumentException> { call, cause ->
            logger.warn("Bad request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Invalid request")))
        }

        exception<IllegalStateException> { call, cause ->
            logger.warn("Conflict: {}", cause.message)
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "Conflict")))
        }

        // Catch-all for unexpected exceptions
        exception<Exception> { call, cause ->
            logger.error("Unexpected error: {}", cause.message, cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }
}

