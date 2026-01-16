package io.deepsearch.domain.exceptions

/**
 * User-friendly error categories for URL processing failures.
 * 
 * These categories are designed to:
 * 1. Be understandable by non-technical users
 * 2. Group related technical errors into coherent buckets
 * 3. Allow frontend to determine appropriate UI actions based on category
 */
enum class ErrorCategory(
    val displayName: String,
    val description: String
) {
    /**
     * Website is using bot protection (Cloudflare, CAPTCHA, etc.)
     * that is actively blocking our crawler.
     */
    ACCESS_BLOCKED(
        displayName = "Website Protection Detected",
        description = "The website is using protection measures that are blocking our crawler."
    ),

    /**
     * Network connectivity issues - timeout, DNS failure, SSL errors.
     * Could be temporary or indicate infrastructure problems.
     */
    CONNECTION_FAILED(
        displayName = "Connection Issue",
        description = "Could not establish a connection to the website."
    ),

    /**
     * Website is down or experiencing issues (5xx errors).
     */
    WEBSITE_UNAVAILABLE(
        displayName = "Website Unavailable",
        description = "The website is currently unavailable or experiencing issues."
    ),

    /**
     * Website requires authentication or explicitly denies access (401, 403 non-bot).
     */
    ACCESS_RESTRICTED(
        displayName = "Access Restricted",
        description = "The website requires authentication or has restricted access."
    ),

    /**
     * Page not found or content removed (404, 410).
     */
    NOT_FOUND(
        displayName = "Page Not Found",
        description = "The page doesn't exist or has been removed."
    ),

    /**
     * Rate limited by the website (429).
     */
    RATE_LIMITED(
        displayName = "Too Many Requests",
        description = "The website is limiting our request rate."
    ),

    /**
     * Content cannot be processed (unsupported type, too large, etc.)
     */
    CONTENT_UNSUPPORTED(
        displayName = "Content Not Supported",
        description = "The content type is not supported for processing."
    ),

    /**
     * Internal processing error (markdown extraction, parsing, etc.)
     */
    PROCESSING_ERROR(
        displayName = "Processing Error",
        description = "An error occurred while processing the content."
    ),

    /**
     * All configured proxies failed to access the URL.
     */
    ALL_PROXIES_FAILED(
        displayName = "All Proxies Failed",
        description = "All available proxies failed to access the website."
    ),

    /**
     * Unknown or uncategorized error.
     */
    UNKNOWN(
        displayName = "Unknown Error",
        description = "An unexpected error occurred."
    );

    companion object {
        /**
         * Get the appropriate category for an exception.
         */
        fun fromException(exception: UrlProcessingException): ErrorCategory {
            return ErrorCategorizer.categorize(exception)
        }
    }
}

/**
 * Utility to categorize URL processing exceptions into user-friendly categories.
 */
object ErrorCategorizer {

    /**
     * Categorize a URL processing exception.
     * 
     * @param exception The exception to categorize
     * @return The appropriate error category
     */
    fun categorize(exception: UrlProcessingException): ErrorCategory {
        return when (exception) {
            // Bot blocking - highest priority, check first
            is BotBlockingException -> ErrorCategory.ACCESS_BLOCKED

            // All proxies failed
            is AllProxiesFailedException -> ErrorCategory.ALL_PROXIES_FAILED

            // No proxies available - treat as blocked since we can't bypass
            is NoProxiesAvailableException -> ErrorCategory.ACCESS_BLOCKED

            // Network/connection exceptions
            is NetworkConnectionException -> categorizeNetworkException(exception)

            // Markdown conversion exceptions
            is MarkdownConversionException -> ErrorCategory.PROCESSING_ERROR

            // Browser operation exceptions
            is io.deepsearch.domain.browser.BrowserOperationException -> categorizeBrowserException(exception)

            // Default
            else -> ErrorCategory.UNKNOWN
        }
    }

    private fun categorizeNetworkException(exception: NetworkConnectionException): ErrorCategory {
        return when (exception) {
            // Timeouts, DNS, SSL, connection issues
            is NetworkTimeoutException,
            is DnsResolutionException,
            is SslHandshakeException,
            is ConnectionRefusedException,
            is RedirectLoopException -> ErrorCategory.CONNECTION_FAILED

            // HTTP client errors (4xx)
            is HttpClientErrorException -> categorizeHttpClientError(exception.statusCode)

            // HTTP server errors (5xx)
            is HttpServerErrorException -> categorizeHttpServerError(exception.statusCode)

            // Rate limiting
            is RateLimitExceededException -> ErrorCategory.RATE_LIMITED

            // Content issues
            is UnsupportedContentTypeException,
            is ContentTooLargeException,
            is FileTooLargeException -> ErrorCategory.CONTENT_UNSUPPORTED

            // Browser navigation failures
            is BrowserNavigationException -> ErrorCategory.CONNECTION_FAILED
        }
    }

    private fun categorizeHttpClientError(statusCode: Int): ErrorCategory {
        return when (statusCode) {
            401 -> ErrorCategory.ACCESS_RESTRICTED
            403 -> ErrorCategory.ACCESS_RESTRICTED  // Could be bot blocking, but needs BotBlockingException for that
            404, 410 -> ErrorCategory.NOT_FOUND
            429 -> ErrorCategory.RATE_LIMITED
            else -> ErrorCategory.CONNECTION_FAILED
        }
    }

    private fun categorizeHttpServerError(statusCode: Int): ErrorCategory {
        return when (statusCode) {
            503 -> ErrorCategory.WEBSITE_UNAVAILABLE  // Could be Cloudflare, but needs BotBlockingException for that
            502, 504 -> ErrorCategory.WEBSITE_UNAVAILABLE
            else -> ErrorCategory.WEBSITE_UNAVAILABLE
        }
    }

    private fun categorizeBrowserException(exception: io.deepsearch.domain.browser.BrowserOperationException): ErrorCategory {
        // Browser exceptions carry error codes that might indicate specific issues
        return when {
            exception.code.contains("TIMEOUT", ignoreCase = true) -> ErrorCategory.CONNECTION_FAILED
            exception.code.contains("DNS", ignoreCase = true) -> ErrorCategory.CONNECTION_FAILED
            exception.code.contains("SSL", ignoreCase = true) -> ErrorCategory.CONNECTION_FAILED
            exception.code.contains("BLOCKED", ignoreCase = true) -> ErrorCategory.ACCESS_BLOCKED
            exception.code.contains("403", ignoreCase = true) -> ErrorCategory.ACCESS_RESTRICTED
            exception.code.contains("404", ignoreCase = true) -> ErrorCategory.NOT_FOUND
            exception.code.contains("5", ignoreCase = true) -> ErrorCategory.WEBSITE_UNAVAILABLE
            else -> ErrorCategory.PROCESSING_ERROR
        }
    }
}

/**
 * Extension function to get category for any UrlProcessingException.
 */
val UrlProcessingException.category: ErrorCategory
    get() = ErrorCategorizer.categorize(this)

/**
 * Data class representing a categorized error with all information needed for API responses.
 */
data class CategorizedError(
    val category: ErrorCategory,
    val errorCode: String,
    val url: String,
    val domain: String,
    val userMessage: String,
    val technicalDetails: String?
) {
    companion object {
        /**
         * Create a CategorizedError from a UrlProcessingException.
         */
        fun from(exception: UrlProcessingException): CategorizedError {
            val category = exception.category
            val domain = extractDomain(exception.url)

            return CategorizedError(
                category = category,
                errorCode = deriveErrorCode(exception),
                url = exception.url,
                domain = domain,
                userMessage = formatUserMessage(category, domain, exception),
                technicalDetails = exception.message
            )
        }

        private fun extractDomain(url: String): String {
            return try {
                java.net.URI(url).host ?: url
            } catch (e: Exception) {
                url
            }
        }

        private fun deriveErrorCode(exception: UrlProcessingException): String {
            return when (exception) {
                is BotBlockingException -> exception.errorCode
                is HttpClientErrorException -> "HTTP_${exception.statusCode}"
                is HttpServerErrorException -> "HTTP_${exception.statusCode}"
                is NetworkTimeoutException -> "TIMEOUT"
                is DnsResolutionException -> "DNS_FAILURE"
                is SslHandshakeException -> "SSL_ERROR"
                is ConnectionRefusedException -> "CONNECTION_REFUSED"
                is RedirectLoopException -> "REDIRECT_LOOP"
                is RateLimitExceededException -> "RATE_LIMITED"
                is UnsupportedContentTypeException -> "UNSUPPORTED_CONTENT"
                is ContentTooLargeException -> "CONTENT_TOO_LARGE"
                is FileTooLargeException -> "FILE_TOO_LARGE"
                is BrowserNavigationException -> "NAVIGATION_FAILED"
                is AllProxiesFailedException -> "ALL_PROXIES_FAILED"
                is NoProxiesAvailableException -> "NO_PROXIES"
                is MarkdownExtractionException -> "MARKDOWN_EXTRACTION"
                is ParsingException -> "PARSING_ERROR"
                is FileIngestionException -> "FILE_INGESTION"
                is io.deepsearch.domain.browser.BrowserOperationException -> exception.code
                else -> "UNKNOWN"
            }
        }

        private fun formatUserMessage(
            category: ErrorCategory,
            domain: String,
            exception: UrlProcessingException
        ): String {
            return when (exception) {
                is BotBlockingException -> when (exception.errorCode) {
                    BotBlockingException.CLOUDFLARE_BLOCKED ->
                        "$domain is using Cloudflare protection which is blocking our crawler."
                    BotBlockingException.CAPTCHA_DETECTED ->
                        "$domain requires human verification (CAPTCHA) that we cannot complete."
                    BotBlockingException.BOT_DETECTED ->
                        "$domain has detected automated access and is blocking our crawler."
                    else ->
                        "$domain is blocking automated access."
                }

                is NetworkTimeoutException ->
                    "Could not establish a connection to $domain. The website may be slow or your network may have issues."

                is DnsResolutionException ->
                    "Could not resolve $domain. Please check the URL is correct."

                is SslHandshakeException ->
                    "Secure connection to $domain failed. The website may have certificate issues."

                is HttpServerErrorException -> when (exception.statusCode) {
                    503 -> "$domain is currently unavailable (503 Service Unavailable)."
                    502 -> "$domain returned a bad gateway error (502)."
                    504 -> "$domain timed out (504 Gateway Timeout)."
                    else -> "$domain is experiencing server issues (${exception.statusCode})."
                }

                is HttpClientErrorException -> when (exception.statusCode) {
                    401 -> "$domain requires authentication to access this content."
                    403 -> "Access to $domain is forbidden."
                    404 -> "The page at ${exception.url} doesn't exist or has been moved."
                    410 -> "The page at ${exception.url} has been permanently removed."
                    429 -> "Too many requests to $domain. Please wait before trying again."
                    else -> "$domain returned an error (${exception.statusCode})."
                }

                is RateLimitExceededException ->
                    "$domain is rate limiting our requests. Please wait before trying again."

                is AllProxiesFailedException ->
                    "All ${exception.attemptedProxyCount} proxies failed to access $domain."

                is UnsupportedContentTypeException ->
                    "The content type (${exception.contentType}) is not supported."

                is ContentTooLargeException ->
                    "The content is too large to process."

                else -> category.description
            }
        }
    }
}
