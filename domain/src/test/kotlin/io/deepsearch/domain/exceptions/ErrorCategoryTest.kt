package io.deepsearch.domain.exceptions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ErrorCategoryTest {

    @Test
    fun `BotBlockingException should categorize as ACCESS_BLOCKED`() {
        val exception = BotBlockingException(
            url = "https://example.com",
            errorCode = BotBlockingException.CLOUDFLARE_BLOCKED,
            detectionMethod = BotBlockingException.DetectionMethod.HTTP_HEADER,
            details = "cf-ray header detected"
        )

        val category = exception.category

        assertEquals(ErrorCategory.ACCESS_BLOCKED, category)
        assertEquals("Website Protection Detected", category.displayName)
    }

    @Test
    fun `HttpClientErrorException 404 should categorize as NOT_FOUND`() {
        val exception = HttpClientErrorException(
            url = "https://example.com/missing-page",
            statusCode = 404,
            reasonPhrase = "Not Found"
        )

        val category = exception.category

        assertEquals(ErrorCategory.NOT_FOUND, category)
        assertEquals("Page Not Found", category.displayName)
    }

    @Test
    fun `HttpClientErrorException 401 should categorize as ACCESS_RESTRICTED`() {
        val exception = HttpClientErrorException(
            url = "https://example.com/private",
            statusCode = 401,
            reasonPhrase = "Unauthorized"
        )

        assertEquals(ErrorCategory.ACCESS_RESTRICTED, exception.category)
    }

    @Test
    fun `HttpClientErrorException 429 should categorize as RATE_LIMITED`() {
        val exception = HttpClientErrorException(
            url = "https://example.com/api",
            statusCode = 429,
            reasonPhrase = "Too Many Requests"
        )

        assertEquals(ErrorCategory.RATE_LIMITED, exception.category)
    }

    @Test
    fun `HttpServerErrorException 503 should categorize as WEBSITE_UNAVAILABLE`() {
        val exception = HttpServerErrorException(
            url = "https://example.com",
            statusCode = 503,
            reasonPhrase = "Service Unavailable"
        )

        assertEquals(ErrorCategory.WEBSITE_UNAVAILABLE, exception.category)
    }

    @Test
    fun `NetworkTimeoutException should categorize as CONNECTION_FAILED`() {
        val exception = NetworkTimeoutException(url = "https://slow-server.com")

        assertEquals(ErrorCategory.CONNECTION_FAILED, exception.category)
    }

    @Test
    fun `DnsResolutionException should categorize as CONNECTION_FAILED`() {
        val exception = DnsResolutionException(url = "https://nonexistent.invalid")

        assertEquals(ErrorCategory.CONNECTION_FAILED, exception.category)
    }

    @Test
    fun `SslHandshakeException should categorize as CONNECTION_FAILED`() {
        val exception = SslHandshakeException(url = "https://bad-cert.example.com")

        assertEquals(ErrorCategory.CONNECTION_FAILED, exception.category)
    }

    @Test
    fun `RateLimitExceededException should categorize as RATE_LIMITED`() {
        val exception = RateLimitExceededException(
            url = "https://example.com/api",
            retriesAttempted = 3
        )

        assertEquals(ErrorCategory.RATE_LIMITED, exception.category)
    }

    @Test
    fun `UnsupportedContentTypeException should categorize as CONTENT_UNSUPPORTED`() {
        val exception = UnsupportedContentTypeException(
            url = "https://example.com/video.mp4",
            contentType = "video/mp4"
        )

        assertEquals(ErrorCategory.CONTENT_UNSUPPORTED, exception.category)
    }

    @Test
    fun `AllProxiesFailedException should categorize as ALL_PROXIES_FAILED`() {
        val exception = AllProxiesFailedException(
            url = "https://blocked-site.com",
            attemptedProxyCount = 5
        )

        assertEquals(ErrorCategory.ALL_PROXIES_FAILED, exception.category)
    }

    @Test
    fun `MarkdownExtractionException should categorize as PROCESSING_ERROR`() {
        val exception = MarkdownExtractionException(
            url = "https://example.com",
            cause = RuntimeException("Parse error")
        )

        assertEquals(ErrorCategory.PROCESSING_ERROR, exception.category)
    }

    @Test
    fun `CategorizedError from BotBlockingException contains all fields`() {
        val exception = BotBlockingException(
            url = "https://example.com/page",
            errorCode = BotBlockingException.CLOUDFLARE_BLOCKED,
            detectionMethod = BotBlockingException.DetectionMethod.PAGE_TITLE,
            details = "Page title: 'Just a moment...'"
        )

        val categorized = CategorizedError.from(exception)

        assertEquals(ErrorCategory.ACCESS_BLOCKED, categorized.category)
        assertEquals(BotBlockingException.CLOUDFLARE_BLOCKED, categorized.errorCode)
        assertEquals("https://example.com/page", categorized.url)
        assertEquals("example.com", categorized.domain)
        assertTrue(categorized.userMessage.contains("Cloudflare"))
    }

    @Test
    fun `CategorizedError from HttpClientErrorException 404 has correct user message`() {
        val exception = HttpClientErrorException(
            url = "https://example.com/missing",
            statusCode = 404,
            reasonPhrase = "Not Found"
        )

        val categorized = CategorizedError.from(exception)

        assertEquals("HTTP_404", categorized.errorCode)
        assertTrue(categorized.userMessage.contains("doesn't exist"))
    }

    @Test
    fun `BotBlockingException isCloudflare returns true for Cloudflare errors`() {
        val cfException = BotBlockingException(
            url = "https://example.com",
            errorCode = BotBlockingException.CLOUDFLARE_JS_CHALLENGE
        )

        assertTrue(cfException.isCloudflare)
        assertFalse(cfException.isCaptcha)

        val captchaException = BotBlockingException(
            url = "https://example.com",
            errorCode = BotBlockingException.CLOUDFLARE_CAPTCHA
        )

        assertTrue(captchaException.isCloudflare)
        assertTrue(captchaException.isCaptcha)
    }

    @Test
    fun `BotBlockingException isBlockingError returns true for all known error codes`() {
        val knownCodes = listOf(
            BotBlockingException.BLOCKED_403,
            BotBlockingException.BLOCKED_503,
            BotBlockingException.CLOUDFLARE_BLOCKED,
            BotBlockingException.CLOUDFLARE_JS_CHALLENGE,
            BotBlockingException.CLOUDFLARE_CAPTCHA,
            BotBlockingException.CAPTCHA_DETECTED,
            BotBlockingException.ACCESS_DENIED,
            BotBlockingException.BOT_DETECTED,
            BotBlockingException.AKAMAI_BLOCKED,
            BotBlockingException.IMPERVA_BLOCKED,
            BotBlockingException.DATADOME_BLOCKED
        )

        knownCodes.forEach { code ->
            assertTrue(BotBlockingException.isBlockingError(code), "Expected $code to be a blocking error")
        }

        assertFalse(BotBlockingException.isBlockingError("UNKNOWN_ERROR"))
    }
}
