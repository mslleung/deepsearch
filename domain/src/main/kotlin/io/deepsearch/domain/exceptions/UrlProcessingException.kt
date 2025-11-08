package io.deepsearch.domain.exceptions

/**
 * Base sealed class for all URL processing exceptions.
 * Each subclass represents a specific failure mode with type safety.
 * 
 * Exception message is used to store the reason/details of the failure.
 * The exception type itself (class name) is used to categorize the failure.
 */
sealed class UrlProcessingException(
    val url: String,
    message: String,
    cause: Throwable? = null
) : Exception("$url: $message", cause)

// Network/connection exceptions (out of our control) - fail discovered links gracefully

/**
 * Base class for network and connection-related failures.
 * These exceptions are expected when processing discovered links and should be handled gracefully.
 */
sealed class NetworkConnectionException(
    url: String,
    message: String,
    cause: Throwable? = null
) : UrlProcessingException(url, message, cause)

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
) : NetworkConnectionException(url, "HTTP $statusCode: $reasonPhrase", cause)

/**
 * HTTP 5xx server error.
 */
class HttpServerErrorException(
    url: String,
    val statusCode: Int,
    val reasonPhrase: String,
    cause: Throwable? = null
) : NetworkConnectionException(url, "HTTP $statusCode: $reasonPhrase", cause)

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
    message: String,
    cause: Throwable
) : UrlProcessingException(url, message, cause)

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
