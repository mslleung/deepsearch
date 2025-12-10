package io.deepsearch.domain.exceptions

/**
 * Base class for all URL processing exceptions.
 * Each subclass represents a specific failure mode with type safety.
 * 
 * The [reason] property stores a user-friendly description of the failure.
 * The exception type itself (class name) is used to categorize the failure.
 * 
 * This class is open to allow extension from other packages in the domain module
 * (e.g., browser exceptions).
 */
open class UrlProcessingException(
    val url: String,
    val reason: String,
    cause: Throwable? = null
) : Exception(reason, cause)

// Network/connection exceptions (out of our control) - fail discovered links gracefully

/**
 * Base class for network and connection-related failures.
 * These exceptions are expected when processing discovered links and should be handled gracefully.
 */
sealed class NetworkConnectionException(
    url: String,
    reason: String,
    cause: Throwable? = null
) : UrlProcessingException(url, reason, cause)

/**
 * Network timeout while attempting to reach the URL.
 */
class NetworkTimeoutException(
    url: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "Network timeout", cause)

/**
 * Connection refused by the remote server.
 */
class ConnectionRefusedException(
    url: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "Connection refused", cause)

/**
 * DNS resolution failed for the domain.
 */
class DnsResolutionException(
    url: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "DNS resolution failed", cause)

/**
 * SSL/TLS handshake failed.
 */
class SslHandshakeException(
    url: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "SSL handshake failed", cause)

/**
 * Too many redirects or redirect loop detected.
 */
class RedirectLoopException(
    url: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "Redirect loop detected", cause)

/**
 * HTTP 4xx client error (e.g., 404, 403, 401).
 */
class HttpClientErrorException(
    url: String,
    val statusCode: Int,
    val reasonPhrase: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, formatHttpClientError(statusCode, reasonPhrase), cause)

private fun formatHttpClientError(statusCode: Int, reasonPhrase: String): String = when (statusCode) {
    400 -> "Bad request"
    401 -> "Authentication required"
    403 -> "Access forbidden"
    404 -> "Page not found"
    405 -> "Method not allowed"
    408 -> "Request timeout"
    410 -> "Page no longer available"
    429 -> "Too many requests"
    else -> reasonPhrase.ifBlank { "Client error ($statusCode)" }
}

/**
 * HTTP 5xx server error.
 */
class HttpServerErrorException(
    url: String,
    val statusCode: Int,
    val reasonPhrase: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, formatHttpServerError(statusCode, reasonPhrase), cause)

private fun formatHttpServerError(statusCode: Int, reasonPhrase: String): String = when (statusCode) {
    500 -> "Server error"
    502 -> "Bad gateway"
    503 -> "Service unavailable"
    504 -> "Gateway timeout"
    else -> reasonPhrase.ifBlank { "Server error ($statusCode)" }
}

/**
 * Rate limit exceeded after exhausting all retries.
 * This is thrown when the adaptive rate limiter has attempted multiple retries
 * with exponential backoff but continues to receive HTTP 429 responses.
 */
class RateLimitExceededException(
    url: String,
    val retriesAttempted: Int,
    cause: Throwable? = null
) : NetworkConnectionException(
    url,
    "Rate limit exceeded after $retriesAttempted retries",
    cause
)

/**
 * Content type not supported (e.g., video, audio).
 */
class UnsupportedContentTypeException(
    url: String,
    val contentType: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "Unsupported content type: $contentType", cause)

/**
 * Content size exceeds processing limits.
 */
class ContentTooLargeException(
    url: String,
    val sizeBytes: Long,
    cause: Throwable? = null
) : NetworkConnectionException(url, "Content too large: $sizeBytes bytes", cause)

/**
 * File exceeds the maximum size limit for processing.
 */
class FileTooLargeException(
    url: String,
    val sizeBytes: Long,
    val maxSizeBytes: Long,
    cause: Throwable? = null
) : NetworkConnectionException(url, "File too large: $sizeBytes bytes (max: $maxSizeBytes bytes)", cause)

/**
 * Browser failed to navigate to the URL.
 * This includes Playwright-specific navigation errors.
 */
class BrowserNavigationException(
    url: String,
    cause: Throwable
) : NetworkConnectionException(url, "Browser navigation failed: ${cause.message}", cause)

// Markdown conversion exceptions (in our control) - fail discovered links gracefully

/**
 * Base class for markdown extraction and parsing failures.
 * These exceptions indicate issues in our processing logic.
 */
sealed class MarkdownConversionException(
    url: String,
    reason: String,
    cause: Throwable
) : UrlProcessingException(url, reason, cause)

/**
 * Markdown extraction from page failed.
 */
class MarkdownExtractionException(
    url: String,
    cause: Throwable
) : MarkdownConversionException(url, "Markdown extraction failed: ${cause.message}", cause)

/**
 * Parsing error during content processing.
 */
class ParsingException(
    url: String,
    cause: Throwable
) : MarkdownConversionException(url, "Parsing error: ${cause.message}", cause)

/**
 * File ingestion into Gemini File Search failed.
 */
class FileIngestionException(
    url: String,
    cause: Throwable
) : MarkdownConversionException(url, "File ingestion failed: ${cause.message}", cause)