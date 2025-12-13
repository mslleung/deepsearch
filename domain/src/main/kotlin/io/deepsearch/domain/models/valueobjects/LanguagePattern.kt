package io.deepsearch.domain.models.valueobjects

import java.net.URI

/**
 * Represents a language filter pattern for URL filtering.
 * 
 * Users can specify either:
 * - Path pattern: `/en-us/` - matches URLs containing this path segment
 * - Query pattern: `?lang=en` - matches URLs with this query parameter
 * 
 * URLs without any language indicator are always accepted.
 * The base URL is always accepted regardless of pattern.
 */
sealed class LanguagePattern {
    
    /**
     * Check if a URL matches this language pattern.
     * 
     * @param url The URL to check
     * @param baseUrl The base URL of the site (always allowed)
     * @return true if the URL should be crawled, false if it should be skipped
     */
    abstract fun matches(url: String, baseUrl: String): Boolean
    
    /**
     * Path-based language pattern like `/en-us/`.
     * Matches URLs that contain this path segment.
     * Rejects URLs that contain a different language path segment.
     */
    data class PathPattern(val segment: String) : LanguagePattern() {
        private val normalizedSegment = segment.lowercase().trim('/')
        private val pattern = Regex("/${Regex.escape(normalizedSegment)}/", RegexOption.IGNORE_CASE)
        
        // Pattern to detect any language segment in path
        // Matches: /en/, /en-us/, /en_us/, /zh-cn/, etc.
        private val anyLangPattern = Regex("/([a-z]{2}(-[a-z]{2,3})?)/", RegexOption.IGNORE_CASE)
        
        override fun matches(url: String, baseUrl: String): Boolean {
            // Base URL always allowed
            if (isBaseUrl(url, baseUrl)) return true
            
            val urlPath = try {
                URI(url).path ?: return true
            } catch (e: Exception) {
                return true // If we can't parse, allow it
            }
            
            // If URL has our language segment, accept
            if (pattern.containsMatchIn(urlPath)) return true
            
            // If URL has no language segment at all, accept
            if (!anyLangPattern.containsMatchIn(urlPath)) return true
            
            // URL has a different language segment, reject
            return false
        }
        
        override fun toString(): String = "/$normalizedSegment/"
    }
    
    /**
     * Query parameter-based language pattern like `?lang=en`.
     * Matches URLs that have this query parameter with the specified value.
     * Accepts URLs that don't have this query parameter at all.
     */
    data class QueryPattern(val paramName: String, val paramValue: String) : LanguagePattern() {
        private val normalizedParamName = paramName.lowercase().trim()
        private val normalizedParamValue = paramValue.lowercase().trim()
        
        override fun matches(url: String, baseUrl: String): Boolean {
            // Base URL always allowed
            if (isBaseUrl(url, baseUrl)) return true
            
            val query = try {
                URI(url).query
            } catch (e: Exception) {
                return true // If we can't parse, allow it
            }
            
            // No query string = no language indicator, accept
            if (query.isNullOrBlank()) return true
            
            // Parse query parameters
            val params = query.split("&").associate { param ->
                val parts = param.split("=", limit = 2)
                parts[0].lowercase() to (parts.getOrNull(1)?.lowercase() ?: "")
            }
            
            val actualValue = params[normalizedParamName]
            
            // If our param exists, check if value matches
            if (actualValue != null) {
                return actualValue == normalizedParamValue
            }
            
            // Param doesn't exist = URL has no language indicator for this param, accept
            return true
        }
        
        override fun toString(): String = "?$normalizedParamName=$normalizedParamValue"
    }
    
    companion object {
        /**
         * Parse a pattern string into a LanguagePattern.
         * 
         * @param pattern The pattern string (e.g., "/en-us/" or "?lang=en")
         * @return The parsed LanguagePattern, or null if invalid
         */
        fun parse(pattern: String): LanguagePattern? {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) return null
            
            return when {
                trimmed.startsWith("/") && trimmed.endsWith("/") && trimmed.length >= 3 -> {
                    PathPattern(trimmed)
                }
                trimmed.startsWith("?") && trimmed.contains("=") -> {
                    val paramPart = trimmed.removePrefix("?")
                    val parts = paramPart.split("=", limit = 2)
                    val name = parts[0].trim()
                    val value = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isNotBlank() && value.isNotBlank()) {
                        QueryPattern(name, value)
                    } else null
                }
                else -> null
            }
        }
        
        /**
         * Validate a pattern string and return an error message if invalid.
         * 
         * @param pattern The pattern string to validate
         * @return Error message if invalid, or null if valid (including empty string)
         */
        fun validate(pattern: String): String? {
            val trimmed = pattern.trim()
            
            return when {
                trimmed.isEmpty() -> null // Empty is valid (no filter)
                
                trimmed.startsWith("/") -> {
                    when {
                        !trimmed.endsWith("/") -> 
                            "Path pattern must end with '/'. Example: /en-us/"
                        trimmed.length < 3 -> 
                            "Path pattern too short. Example: /en/"
                        else -> null // Valid
                    }
                }
                
                trimmed.startsWith("?") -> {
                    when {
                        !trimmed.contains("=") -> 
                            "Query pattern must include '='. Example: ?lang=en"
                        else -> {
                            val paramPart = trimmed.removePrefix("?")
                            val parts = paramPart.split("=", limit = 2)
                            val name = parts[0].trim()
                            val value = parts.getOrNull(1)?.trim() ?: ""
                            when {
                                name.isBlank() -> 
                                    "Query pattern needs a parameter name. Example: ?lang=en"
                                value.isBlank() -> 
                                    "Query pattern needs a parameter value. Example: ?lang=en"
                                else -> null // Valid
                            }
                        }
                    }
                }
                
                else -> "Pattern must start with '/' for path or '?' for query parameter"
            }
        }
        
        /**
         * Check if a URL is the base URL (with or without trailing slash).
         */
        private fun isBaseUrl(url: String, baseUrl: String): Boolean {
            val normalizedUrl = url.trimEnd('/')
            val normalizedBase = baseUrl.trimEnd('/')
            return normalizedUrl == normalizedBase
        }
    }
}

