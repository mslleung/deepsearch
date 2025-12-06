package io.deepsearch.presentation.config

import io.deepsearch.domain.config.JwtConfig
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import kotlin.time.Duration.Companion.minutes

/**
 * Rate limit provider names for different authentication contexts.
 */
object RateLimitProviders {
    /** Heavy protection for unauthenticated routes (register, login, etc.) */
    val PUBLIC = RateLimitName("public")
    
    /** User-based limits for JWT-authenticated web-app routes */
    val WEB_APP = RateLimitName("web-app")
    
    /** Dynamic API key limits for search operations based on key type (prefix-based detection) */
    val API_KEY = RateLimitName("api-key")
    
    /** General API key limits for non-search operations (60 req/min for all API keys) */
    val API_KEY_GENERAL = RateLimitName("api-key-general")
}

/**
 * Base rate limit for API keys (uses the highest limit as the base).
 * Individual API key types get effective limits via requestWeight.
 */
private val API_KEY_BASE_LIMIT = ApiKeyType.entries.maxOf { it.rateLimitPerMinute }

/**
 * Configures rate limiting for the application with three strategies:
 * 1. Public routes: Heavy IP-based limiting (5 req/min per IP)
 * 2. Web-app routes: User-based limiting (60 req/min per user)
 * 3. API key routes: Type-based limiting via requestWeight (PLAYGROUND=2/min, REGULAR=20/min)
 */
fun Application.configureRateLimit() {
    install(RateLimit) {
        // Heavy protection for unauthenticated routes (register, login, OAuth)
        register(RateLimitProviders.PUBLIC) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.origin.remoteHost
            }
        }

        // User-based limits for JWT-authenticated web-app routes
        register(RateLimitProviders.WEB_APP) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                // Try to get user ID from JWT, fallback to IP if not authenticated
                call.principal<JWTPrincipal>()
                    ?.payload
                    ?.getClaim(JwtConfig.CLAIM_USER_ID)
                    ?.asString()
                    ?: call.request.origin.remoteHost
            }
        }

        // API key rate limiting with dynamic weights based on key type (prefix)
        // Each API key gets its own bucket; weight determines effective limit
        // Example: Base limit = 20, PLAYGROUND weight = 10 → effective limit = 2 req/min
        register(RateLimitProviders.API_KEY) {
            rateLimiter(limit = API_KEY_BASE_LIMIT, refillPeriod = 1.minutes)
            
            requestKey { call ->
                extractApiKeyFromRequest(call)
            }
            
            requestWeight { call, key ->
                // Determine weight based on API key prefix
                // Higher weight = fewer allowed requests
                val apiKey = key as? String ?: return@requestWeight 1
                val apiKeyType = getApiKeyTypeFromPrefix(apiKey)
                
                // Weight = baseLimit / typeLimit
                // e.g., PLAYGROUND: 20/2 = 10 (so 20 tokens / 10 per request = 2 requests)
                // e.g., REGULAR: 20/20 = 1 (so 20 tokens / 1 per request = 20 requests)
                if (apiKeyType != null) {
                    API_KEY_BASE_LIMIT / apiKeyType.rateLimitPerMinute
                } else {
                    1 // Unknown prefix - use base limit (will fail auth anyway)
                }
            }
        }

        // General API key rate limiting for non-search operations (60 req/min)
        register(RateLimitProviders.API_KEY_GENERAL) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)

            requestKey { call ->
                extractApiKeyFromRequest(call)
            }
        }
    }
}

/**
 * Extracts the API key from the request.
 * Checks bearer token first, then falls back to query parameter (for SSE).
 */
private fun extractApiKeyFromRequest(call: ApplicationCall): String {
    // Try bearer token first (from authenticated principal)
    val bearerToken = call.principal<UserIdPrincipal>()?.name
    if (bearerToken != null) {
        return bearerToken
    }
    
    // Fall back to query parameter (used by SSE endpoints)
    return call.request.queryParameters["apiKey"] ?: call.request.origin.remoteHost
}

/**
 * Determines the API key type from its prefix.
 * Returns null if the prefix doesn't match any known type.
 * 
 * Current prefixes:
 * - ds_test_ → PLAYGROUND (2 req/min)
 * - ds_live_ → REGULAR (20 req/min)
 * 
 * Future: Could be extended to look up pricing plan limits from database.
 */
private fun getApiKeyTypeFromPrefix(apiKey: String): ApiKeyType? {
    return ApiKeyType.entries.find { apiKey.startsWith(it.prefix) }
}
