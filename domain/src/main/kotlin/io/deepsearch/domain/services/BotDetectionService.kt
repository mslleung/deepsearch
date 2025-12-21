package io.deepsearch.domain.services

import io.deepsearch.domain.exceptions.BotBlockingException
import org.slf4j.LoggerFactory

/**
 * Service to detect bot blocking patterns in page HTML content.
 *
 * Detects:
 * - Cloudflare challenge pages
 * - Captcha/challenge pages (reCAPTCHA, hCaptcha)
 * - Access denied / 403 pages
 * - Generic bot detection systems
 */
interface IBotDetectionService {
    /**
     * Analyze page HTML for blocking patterns.
     * 
     * @param url The URL that was accessed
     * @param html The page HTML content
     * @throws BotBlockingException if blocking is detected
     */
    fun checkForBlocking(url: String, html: String)

    /**
     * Analyze page HTML and return detection result without throwing.
     * 
     * @param url The URL that was accessed
     * @param html The page HTML content
     * @return Detection result or null if no blocking detected
     */
    fun detectBlocking(url: String, html: String): DetectionResult?
}

/**
 * Result of bot detection analysis.
 */
data class DetectionResult(
    val errorCode: String,
    val details: String
)

class BotDetectionService : IBotDetectionService {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // Cloudflare patterns
        private val CLOUDFLARE_PATTERNS = listOf(
            "cf-browser-verification",
            "cf_chl_opt",
            "cf-challenge",
            "checking your browser",
            "ray id:",
            "cloudflare",
            "just a moment",
            "enable javascript and cookies to continue",
            "cf-wrapper",
            "cf-error",
            "__cf_chl_tk"
        )

        // Captcha patterns
        private val CAPTCHA_PATTERNS = listOf(
            "recaptcha",
            "hcaptcha",
            "g-recaptcha",
            "h-captcha",
            "captcha-container",
            "captcha_container",
            "verify you are human",
            "prove you're human",
            "i'm not a robot",
            "complete the security check",
            "robot verification"
        )

        // Access denied patterns
        private val ACCESS_DENIED_PATTERNS = listOf(
            "access denied",
            "access forbidden",
            "403 forbidden",
            "you don't have permission",
            "permission denied",
            "blocked",
            "your ip has been blocked",
            "ip address has been blocked",
            "too many requests",
            "rate limit exceeded"
        )

        // Bot detection patterns
        private val BOT_DETECTION_PATTERNS = listOf(
            "bot detected",
            "automated access",
            "suspicious activity",
            "unusual traffic",
            "automated request",
            "please verify yourself",
            "security check",
            "human verification"
        )
    }

    override fun checkForBlocking(url: String, html: String) {
        val result = detectBlocking(url, html)
        if (result != null) {
            throw BotBlockingException(url, result.errorCode, result.details)
        }
    }

    override fun detectBlocking(url: String, html: String): DetectionResult? {
        val htmlLower = html.lowercase()

        // Check for Cloudflare challenge
        val cloudflareMatch = findMatchedPattern(htmlLower, CLOUDFLARE_PATTERNS)
        if (cloudflareMatch != null) {
            logger.debug("Cloudflare challenge detected for {}: {}", url, cloudflareMatch)
            return DetectionResult(
                BotBlockingException.CLOUDFLARE_BLOCKED,
                "Cloudflare challenge detected: $cloudflareMatch"
            )
        }

        // Check for captcha
        val captchaMatch = findMatchedPattern(htmlLower, CAPTCHA_PATTERNS)
        if (captchaMatch != null) {
            logger.debug("Captcha detected for {}: {}", url, captchaMatch)
            return DetectionResult(
                BotBlockingException.CAPTCHA_DETECTED,
                "Captcha detected: $captchaMatch"
            )
        }

        // Check for access denied
        val accessDeniedMatch = findMatchedPattern(htmlLower, ACCESS_DENIED_PATTERNS)
        if (accessDeniedMatch != null) {
            logger.debug("Access denied detected for {}: {}", url, accessDeniedMatch)
            return DetectionResult(
                BotBlockingException.ACCESS_DENIED,
                "Access denied: $accessDeniedMatch"
            )
        }

        // Check for bot detection
        val botDetectionMatch = findMatchedPattern(htmlLower, BOT_DETECTION_PATTERNS)
        if (botDetectionMatch != null) {
            logger.debug("Bot detection triggered for {}: {}", url, botDetectionMatch)
            return DetectionResult(
                BotBlockingException.BOT_DETECTED,
                "Bot detection triggered: $botDetectionMatch"
            )
        }

        return null
    }

    private fun findMatchedPattern(text: String, patterns: List<String>): String? {
        return patterns.firstOrNull { text.contains(it) }
    }
}

