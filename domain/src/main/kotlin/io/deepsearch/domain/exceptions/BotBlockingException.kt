package io.deepsearch.domain.exceptions

/**
 * Exception thrown when bot blocking is detected during page access.
 * 
 * This exception indicates that the website has blocked access, possibly due to:
 * - HTTP 403/503 status codes
 * - Cloudflare challenge pages
 * - Captcha/verification pages
 * - Other anti-bot systems
 * 
 * When this exception is caught, the bypass strategy should be upgraded to use proxies.
 */
class BotBlockingException(
    url: String,
    val errorCode: String,
    val details: String? = null,
    cause: Throwable? = null
) : UrlProcessingException(url, "Bot blocking detected: $errorCode${details?.let { " - $it" } ?: ""}", cause) {

    companion object {
        const val BLOCKED_403 = "BLOCKED_403"
        const val BLOCKED_503 = "BLOCKED_503"
        const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        const val CAPTCHA_DETECTED = "CAPTCHA_DETECTED"
        const val ACCESS_DENIED = "ACCESS_DENIED"
        const val BOT_DETECTED = "BOT_DETECTED"
        const val NAVIGATION_ERROR = "NAVIGATION_ERROR"

        /**
         * Check if an error code indicates bot blocking.
         */
        fun isBlockingError(errorCode: String): Boolean {
            return errorCode in listOf(
                BLOCKED_403,
                BLOCKED_503,
                CLOUDFLARE_BLOCKED,
                CAPTCHA_DETECTED,
                ACCESS_DENIED,
                BOT_DETECTED
            )
        }
    }
}

/**
 * Exception thrown when all proxies fail during fanout.
 */
class AllProxiesFailedException(
    url: String,
    val attemptedProxyCount: Int,
    cause: Throwable? = null
) : UrlProcessingException(
    url,
    "All $attemptedProxyCount proxies failed to access URL",
    cause
)

/**
 * Exception thrown when no proxies are available.
 */
class NoProxiesAvailableException(
    url: String
) : UrlProcessingException(url, "No free proxies available for bypass")

