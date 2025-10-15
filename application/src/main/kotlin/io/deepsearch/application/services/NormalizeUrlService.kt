package io.deepsearch.application.services

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Configuration for URL normalization behavior.
 */
data class UrlNormalizationConfig(
    /**
     * Whether to strip i18n locale segments from paths.
     * If true, /en/about and /fr/about will be treated as the same URL.
     */
    val stripLocaleFromPath: Boolean = false,
    
    /**
     * If stripLocaleFromPath is true, only strip these specific locales.
     * If empty, strips all detected locale patterns.
     */
    val localeWhitelist: Set<String> = emptySet(),
    
    /**
     * Whether to normalize www subdomain.
     * If true, www.example.com and example.com will be treated as the same URL.
     */
    val normalizeWwwSubdomain: Boolean = false
)

/**
 * Service for normalizing URLs to a canonical form for deduplication purposes.
 */
interface INormalizeUrlService {
    /**
     * Normalizes a URL string to its canonical form.
     * Returns null if the URL is invalid or cannot be normalized.
     *
     * Normalization includes:
     * - Lowercasing scheme and host
     * - Handling IDN/punycode domains
     * - Removing default ports
     * - Normalizing paths (trailing slashes, . and .. segments, double slashes)
     * - Removing fragments
     * - Filtering and sorting query parameters
     * - Optionally stripping locale segments from paths
     * - Optionally normalizing www subdomain
     */
    fun normalize(url: String, config: UrlNormalizationConfig = UrlNormalizationConfig()): String?
    
    /**
     * Extracts the locale from a URL path if present.
     * Returns null if no locale is detected.
     *
     * Examples:
     *   /en/about -> "en"
     *   /fr-ca/products -> "fr-ca"
     *   /about -> null
     */
    fun extractLocale(url: String): String?
}

class NormalizeUrlService : INormalizeUrlService {
    
    companion object {
        /**
         * Common two-letter ISO 639-1 language codes and regional variants.
         */
        private val LOCALE_PATTERNS = setOf(
            // Two-letter language codes (ISO 639-1)
            "en", "fr", "de", "es", "it", "pt", "nl", "pl", "ru", "ja", "ko", "zh", "ar", "hi",
            "he", "tr", "sv", "no", "da", "fi", "cs", "el", "th", "vi", "id", "ms", "ro", "hu",
            "uk", "bg", "hr", "sk", "sl", "lt", "lv", "et", "sr", "ca", "af", "sq", "eu", "gl",
            // Common regional variants (language-region)
            "en-us", "en-gb", "en-ca", "en-au", "en-nz", "en-ie", "en-za", "en-in",
            "fr-fr", "fr-ca", "fr-be", "fr-ch",
            "es-es", "es-mx", "es-ar", "es-co", "es-cl", "es-pe",
            "pt-br", "pt-pt",
            "de-de", "de-at", "de-ch",
            "zh-cn", "zh-tw", "zh-hk", "zh-sg",
            "it-it", "it-ch",
            "nl-nl", "nl-be"
        )
        
        /**
         * Common query parameters that typically don't affect page content.
         * These are removed during normalization.
         * All values are lowercase for case-insensitive comparison.
         */
        private val INSIGNIFICANT_PARAMS = setOf(
            // UTM tracking parameters
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_campaign_id", "utm_source_platform",
            // Click IDs and tracking
            "fbclid", "gclid", "msclkid", "wbraid", "gbraid", "mc_cid", "mc_eid",
            "dclid", "gclsrc", "zanpid", "_openstat",
            // Google Analytics
            "_ga", "_gid", "_gac", "_gl",
            // Language/locale hints (usually in query params, not path)
            "hslang", "hs_lang", "lang", "language", "locale", "l", "hl",
            // Session identifiers
            "sessionid", "session_id", "phpsessid", "jsessionid", "aspsessionid",
            "sid", "s_id", "sess",
            // Cache busting and timestamps
            "_", "t", "timestamp", "ts", "cache", "v", "ver", "version", "nocache",
            // Referrer and source tracking
            "ref", "referrer", "source", "src", "referer", "from",
            // Social media tracking
            "share", "shared", "fbid", "twclid", "igshid",
            // Email campaign tracking
            "mkt_tok", "trk", "trkid", "trackid",
            // Pagination that doesn't change core content
            "offset", "limit", "per_page"
        )
    }
    
    override fun normalize(url: String, config: UrlNormalizationConfig): String? {
        if (url.isBlank()) return null
        
        return try {
            // First, try to convert any IDN domains to ASCII before parsing
            val asciiUrl = convertIdnToAscii(url.trim())
            val uri = URI(asciiUrl)
            
            // Only process http and https schemes
            val scheme = uri.scheme?.lowercase()
            if (scheme == null || (scheme != "http" && scheme != "https")) {
                // For non-http(s) schemes, return null (we don't normalize them)
                return null
            }
            
            // Extract and normalize host
            val rawHost = uri.host?.lowercase() ?: return null
            val host = normalizeHost(rawHost, config)
            
            // Extract port, remove if it's default
            val port = if (uri.port != -1 && !isDefaultPort(scheme, uri.port)) {
                ":${uri.port}"
            } else {
                ""
            }
            
            // Normalize path
            val path = normalizePath(uri.path ?: "", config)
            
            // Normalize query
            val query = normalizeQuery(uri.query)
            
            // Reconstruct URL without fragment
            buildString {
                append(scheme)
                append("://")
                append(host)
                append(port)
                append(path)
                if (query.isNotEmpty()) {
                    append("?")
                    append(query)
                }
            }
        } catch (e: Exception) {
            // Return null for malformed URLs
            null
        }
    }
    
    override fun extractLocale(url: String): String? {
        return try {
            val uri = URI(url.trim())
            val path = uri.path ?: return null
            val segments = path.split("/").filter { it.isNotEmpty() }
            
            if (segments.isEmpty()) return null
            
            val firstSegment = segments.first().lowercase()
            if (firstSegment in LOCALE_PATTERNS) firstSegment else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun isDefaultPort(scheme: String, port: Int): Boolean {
        return (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    }
    
    /**
     * Converts any IDN (Internationalized Domain Names) in the URL to ASCII/punycode.
     * This allows URI parsing to work with non-ASCII domain names.
     */
    private fun convertIdnToAscii(url: String): String {
        return try {
            // Match the host part of the URL using a regex
            // Pattern: scheme://host[:port][/path]
            val regex = """^(https?://)([\w\u0080-\uFFFF\-.]+)(:[0-9]+)?(.*)$""".toRegex()
            val match = regex.find(url)
            
            if (match != null) {
                val scheme = match.groupValues[1]
                val host = match.groupValues[2]
                val port = match.groupValues[3]
                val rest = match.groupValues[4]
                
                // Convert host to ASCII
                val asciiHost = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
                
                "$scheme$asciiHost$port$rest"
            } else {
                // If pattern doesn't match, return as-is
                url
            }
        } catch (e: Exception) {
            // If conversion fails, return original URL
            url
        }
    }
    
    private fun normalizeHost(host: String, config: UrlNormalizationConfig): String {
        var normalizedHost = host
        
        // Handle IDN (Internationalized Domain Names) - convert to ASCII punycode
        normalizedHost = try {
            IDN.toASCII(normalizedHost, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (e: Exception) {
            normalizedHost
        }
        
        // Optionally normalize www subdomain
        if (config.normalizeWwwSubdomain && normalizedHost.startsWith("www.")) {
            val withoutWww = normalizedHost.substring(4)
            // Only remove www if there's still a valid domain after
            if (withoutWww.contains(".")) {
                normalizedHost = withoutWww
            }
        }
        
        return normalizedHost
    }
    
    private fun normalizePath(path: String, config: UrlNormalizationConfig): String {
        if (path.isEmpty()) return "/"
        
        // Decode percent encoding
        val decoded = percentDecode(path)
        
        // Split into segments and normalize
        var segments = decoded.split("/").filter { it.isNotEmpty() }
        
        // Resolve . and .. segments
        segments = resolvePathSegments(segments)
        
        // Optionally strip locale from path
        if (config.stripLocaleFromPath && segments.isNotEmpty()) {
            segments = stripLocaleSegment(segments, config.localeWhitelist)
        }
        
        // Reconstruct path
        val normalizedPath = if (segments.isEmpty()) {
            "/"
        } else {
            "/" + segments.joinToString("/")
        }
        
        // Always remove trailing slash except for root
        return if (normalizedPath.length > 1 && normalizedPath.endsWith("/")) {
            normalizedPath.dropLast(1)
        } else {
            normalizedPath
        }
    }
    
    private fun percentDecode(str: String): String {
        return try {
            URLDecoder.decode(str, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            str
        }
    }
    
    private fun resolvePathSegments(segments: List<String>): List<String> {
        val resolved = mutableListOf<String>()
        
        for (segment in segments) {
            when (segment) {
                ".", "" -> {
                    // Skip current directory references and empty segments (double slashes)
                    continue
                }
                ".." -> {
                    // Go up one directory if possible
                    if (resolved.isNotEmpty()) {
                        resolved.removeAt(resolved.size - 1)
                    }
                }
                else -> {
                    resolved.add(segment)
                }
            }
        }
        
        return resolved
    }
    
    private fun stripLocaleSegment(segments: List<String>, whitelist: Set<String>): List<String> {
        if (segments.isEmpty()) return segments
        
        val firstSegment = segments.first().lowercase()
        
        // Check if first segment is a locale pattern
        val isLocale = if (whitelist.isNotEmpty()) {
            firstSegment in whitelist
        } else {
            firstSegment in LOCALE_PATTERNS
        }
        
        return if (isLocale && segments.size > 1) {
            // Strip the locale segment
            segments.drop(1)
        } else {
            segments
        }
    }
    
    private fun normalizeQuery(query: String?): String {
        if (query.isNullOrBlank()) return ""
        
        // Parse query parameters
        val params = query.split("&")
            .mapNotNull { param ->
                if (param.isBlank()) return@mapNotNull null
                
                val parts = param.split("=", limit = 2)
                val key = parts[0].trim()
                if (key.isEmpty()) return@mapNotNull null
                
                val value = parts.getOrNull(1)?.trim() ?: ""
                
                // Skip insignificant parameters (case-insensitive)
                if (key.lowercase() in INSIGNIFICANT_PARAMS) {
                    return@mapNotNull null
                }
                
                // Decode both key and value
                val decodedKey = percentDecode(key)
                val decodedValue = percentDecode(value)
                
                decodedKey to decodedValue
            }
            // Remove duplicates, keeping first occurrence
            .distinctBy { it.first }
            // Sort by parameter name for consistency
            .sortedBy { it.first }
        
        // Rebuild query string
        return params.joinToString("&") { (key, value) ->
            if (value.isEmpty()) key else "$key=$value"
        }
    }
}

