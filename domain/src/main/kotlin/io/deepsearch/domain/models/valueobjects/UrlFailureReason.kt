package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Categorizes why a URL access failed.
 * Distinguishes between failures outside our control (network, external servers)
 * and failures within our control (parsing, processing).
 */
@Serializable
enum class UrlFailureReason {
    // Network-level failures (out of our control)
    /** Network timeout while attempting to reach the URL */
    NETWORK_TIMEOUT,
    
    /** Connection refused by the remote server */
    CONNECTION_REFUSED,
    
    /** DNS resolution failed for the domain */
    DNS_RESOLUTION_FAILED,
    
    /** SSL/TLS handshake failed */
    SSL_HANDSHAKE_FAILED,
    
    // HTTP-level failures (out of our control)
    /** HTTP 4xx client error (e.g., 404, 403, 401) */
    HTTP_4XX_CLIENT_ERROR,
    
    /** HTTP 5xx server error */
    HTTP_5XX_SERVER_ERROR,
    
    /** Too many redirects or redirect loop detected */
    HTTP_REDIRECT_LOOP,
    
    // Content-related failures (partially in our control)
    /** Content type not supported (e.g., video, audio) */
    UNSUPPORTED_CONTENT_TYPE,
    
    /** Content size exceeds processing limits */
    CONTENT_TOO_LARGE,
    
    // Processing failures (in our control)
    /** URL format is invalid or malformed */
    INVALID_URL_FORMAT,
    
    /** Browser failed to navigate to the URL */
    BROWSER_NAVIGATION_FAILED,
    
    /** Markdown extraction from page failed */
    MARKDOWN_EXTRACTION_FAILED,
    
    /** Parsing error during content processing */
    PARSING_ERROR,
    
    // Generic fallback
    /** Other unspecified failure - requires investigation */
    FAILED_OTHER
}


