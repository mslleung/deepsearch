package io.deepsearch.domain.exceptions

/**
 * Exception thrown when bot blocking is detected during page access.
 * 
 * This exception indicates that the website has blocked access, possibly due to:
 * - HTTP 403/503 status codes with bot protection signatures
 * - Cloudflare challenge pages (JavaScript challenge, CAPTCHA, etc.)
 * - Other anti-bot systems (Akamai, Imperva, DataDome, etc.)
 * 
 * When this exception is caught, the bypass strategy should be upgraded to use proxies.
 * 
 * @param url The URL that was blocked
 * @param errorCode The specific blocking error code (see companion object constants)
 * @param detectionMethod How the blocking was detected (for debugging/logging)
 * @param details Additional details about the blocking (e.g., page title, HTTP status)
 * @param cause The underlying exception if any
 */
class BotBlockingException(
    url: String,
    val errorCode: String,
    val detectionMethod: DetectionMethod = DetectionMethod.UNKNOWN,
    val details: String? = null,
    cause: Throwable? = null
) : UrlProcessingException(url, "Bot blocking detected: $errorCode${details?.let { " - $it" } ?: ""}", cause) {

    /**
     * Methods used to detect bot blocking.
     * Multiple methods may be used together for higher confidence.
     */
    enum class DetectionMethod(val description: String) {
        /** Detection via HTTP response headers (e.g., cf-ray for Cloudflare) */
        HTTP_HEADER("Detected via HTTP response headers"),
        
        /** Detection via HTTP status code (403/503 with specific patterns) */
        HTTP_STATUS("Detected via HTTP status code"),
        
        /** Detection via page title matching known patterns */
        PAGE_TITLE("Detected via page title"),
        
        /** Detection via page content/DOM elements */
        PAGE_CONTENT("Detected via page content"),
        
        /** Detection via JavaScript challenge detection */
        JS_CHALLENGE("Detected via JavaScript challenge"),
        
        /** Detection via CAPTCHA presence */
        CAPTCHA("Detected via CAPTCHA presence"),
        
        /** Detection via combination of signals */
        COMBINED("Detected via multiple signals"),
        
        /** Detection method unknown or not specified */
        UNKNOWN("Unknown detection method")
    }

    companion object {
        // Generic blocking codes
        const val BLOCKED_403 = "BLOCKED_403"
        const val BLOCKED_503 = "BLOCKED_503"
        const val ACCESS_DENIED = "ACCESS_DENIED"
        const val BOT_DETECTED = "BOT_DETECTED"
        const val NAVIGATION_ERROR = "NAVIGATION_ERROR"

        // Cloudflare-specific codes
        const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        const val CLOUDFLARE_JS_CHALLENGE = "CLOUDFLARE_JS_CHALLENGE"
        const val CLOUDFLARE_CAPTCHA = "CLOUDFLARE_CAPTCHA"
        const val CLOUDFLARE_TURNSTILE = "CLOUDFLARE_TURNSTILE"
        const val CLOUDFLARE_WAF = "CLOUDFLARE_WAF"

        // Generic CAPTCHA (non-Cloudflare)
        const val CAPTCHA_DETECTED = "CAPTCHA_DETECTED"
        const val RECAPTCHA_DETECTED = "RECAPTCHA_DETECTED"
        const val HCAPTCHA_DETECTED = "HCAPTCHA_DETECTED"

        // Other WAF/bot protection systems
        const val AKAMAI_BLOCKED = "AKAMAI_BLOCKED"
        const val IMPERVA_BLOCKED = "IMPERVA_BLOCKED"
        const val DATADOME_BLOCKED = "DATADOME_BLOCKED"
        const val PERIMETERX_BLOCKED = "PERIMETERX_BLOCKED"

        /**
         * Check if an error code indicates bot blocking.
         */
        fun isBlockingError(errorCode: String): Boolean {
            return errorCode in setOf(
                BLOCKED_403,
                BLOCKED_503,
                ACCESS_DENIED,
                BOT_DETECTED,
                CLOUDFLARE_BLOCKED,
                CLOUDFLARE_JS_CHALLENGE,
                CLOUDFLARE_CAPTCHA,
                CLOUDFLARE_TURNSTILE,
                CLOUDFLARE_WAF,
                CAPTCHA_DETECTED,
                RECAPTCHA_DETECTED,
                HCAPTCHA_DETECTED,
                AKAMAI_BLOCKED,
                IMPERVA_BLOCKED,
                DATADOME_BLOCKED,
                PERIMETERX_BLOCKED
            )
        }

        /**
         * Check if the error code indicates a Cloudflare-specific block.
         */
        fun isCloudflareError(errorCode: String): Boolean {
            return errorCode in setOf(
                CLOUDFLARE_BLOCKED,
                CLOUDFLARE_JS_CHALLENGE,
                CLOUDFLARE_CAPTCHA,
                CLOUDFLARE_TURNSTILE,
                CLOUDFLARE_WAF
            )
        }

        /**
         * Check if the error code indicates a CAPTCHA challenge.
         */
        fun isCaptchaError(errorCode: String): Boolean {
            return errorCode in setOf(
                CAPTCHA_DETECTED,
                RECAPTCHA_DETECTED,
                HCAPTCHA_DETECTED,
                CLOUDFLARE_CAPTCHA,
                CLOUDFLARE_TURNSTILE
            )
        }
    }

    /**
     * Whether this is a Cloudflare-specific block.
     */
    val isCloudflare: Boolean
        get() = isCloudflareError(errorCode)

    /**
     * Whether this block involves a CAPTCHA challenge.
     */
    val isCaptcha: Boolean
        get() = isCaptchaError(errorCode)
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

