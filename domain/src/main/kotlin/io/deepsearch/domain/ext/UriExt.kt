package io.deepsearch.domain.ext

import org.slf4j.LoggerFactory
import java.net.URI

private val logger = LoggerFactory.getLogger("io.deepsearch.domain.ext.UriExt")

/**
 * Safely converts a potentially malformed URL string to a URI object.
 * 
 * This function handles URLs that may contain unencoded spaces or other illegal characters
 * by attempting to parse them directly first, and falling back to encoding if that fails.
 * 
 * Common issues handled:
 * - Unencoded spaces in query parameters
 * - Other illegal characters in the URL
 * 
 * @return A URI object, or throws URISyntaxException if the URL cannot be parsed even after encoding
 */
fun String.toSafeUri(): URI {
    if (this.isBlank()) {
        throw IllegalArgumentException("URL cannot be blank")
    }
    
    val trimmed = this.trim()
    
    // First attempt: try parsing as-is
    return try {
        URI(trimmed)
    } catch (e: Exception) {
        logger.debug("Failed to parse URL directly, attempting to encode: {}", trimmed)
        
        // Second attempt: encode the URL by splitting and encoding parts appropriately
        try {
            encodeAndParseUri(trimmed)
        } catch (encodingException: Exception) {
            logger.warn("Failed to parse URL even after encoding: {}", trimmed, encodingException)
            throw e // Throw original exception for better error context
        }
    }
}

/**
 * Encodes a malformed URL string and parses it into a URI.
 * 
 * This function carefully encodes only the parts that need encoding while preserving
 * the URL structure (scheme, authority, path, query, fragment).
 */
private fun encodeAndParseUri(url: String): URI {
    // Split the URL into components
    val schemeEnd = url.indexOf("://")
    if (schemeEnd == -1) {
        // No scheme, just encode spaces and try
        val encoded = url.replace(" ", "%20")
        return URI(encoded)
    }
    
    val scheme = url.substring(0, schemeEnd)
    var remaining = url.substring(schemeEnd + 3)
    
    // Find the start of path (after authority)
    val pathStart = remaining.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (pathStart == -1) remaining else remaining.substring(0, pathStart)
    remaining = if (pathStart == -1) "" else remaining.substring(pathStart)
    
    // Find query and fragment
    val queryStart = remaining.indexOf('?')
    val fragmentStart = remaining.indexOf('#')
    
    val path = when {
        queryStart != -1 -> remaining.substring(0, queryStart)
        fragmentStart != -1 -> remaining.substring(0, fragmentStart)
        else -> remaining
    }
    
    val query = if (queryStart != -1) {
        val queryEnd = if (fragmentStart != -1 && fragmentStart > queryStart) fragmentStart else remaining.length
        remaining.substring(queryStart + 1, queryEnd)
    } else null
    
    val fragment = if (fragmentStart != -1) {
        remaining.substring(fragmentStart + 1)
    } else null
    
    // Encode each part appropriately
    val encodedPath = encodePath(path)
    val encodedQuery = query?.let { encodeQuery(it) }
    val encodedFragment = fragment?.let { encodeFragment(it) }
    
    // Reconstruct the URL
    val reconstructed = buildString {
        append(scheme)
        append("://")
        append(authority)
        append(encodedPath)
        if (encodedQuery != null) {
            append('?')
            append(encodedQuery)
        }
        if (encodedFragment != null) {
            append('#')
            append(encodedFragment)
        }
    }
    
    logger.debug("Encoded URL: {} -> {}", url, reconstructed)
    return URI(reconstructed)
}

/**
 * Encodes a URL path by replacing spaces and other illegal characters.
 */
private fun encodePath(path: String): String {
    if (path.isEmpty()) return path
    
    // Split by '/' to preserve path structure
    return path.split('/').joinToString("/") { segment ->
        if (segment.isEmpty()) {
            segment
        } else {
            // Encode each segment, but preserve already-encoded sequences
            encodeUrlComponent(segment)
        }
    }
}

/**
 * Encodes a URL query string by replacing spaces and other illegal characters.
 */
private fun encodeQuery(query: String): String {
    if (query.isEmpty()) return query
    
    // Split by '&' to preserve query parameter structure
    return query.split('&').joinToString("&") { param ->
        if (param.isEmpty()) {
            param
        } else {
            // Split by '=' to preserve key=value structure
            val parts = param.split('=', limit = 2)
            if (parts.size == 2) {
                "${encodeUrlComponent(parts[0])}=${encodeUrlComponent(parts[1])}"
            } else {
                encodeUrlComponent(param)
            }
        }
    }
}

/**
 * Encodes a URL fragment by replacing spaces and other illegal characters.
 */
private fun encodeFragment(fragment: String): String {
    return encodeUrlComponent(fragment)
}

/**
 * Encodes a URL component while preserving already-encoded sequences.
 * This is a simple implementation that handles common cases.
 */
private fun encodeUrlComponent(component: String): String {
    if (component.isEmpty()) return component
    
    // Quick check: if it's already properly encoded (no spaces or illegal chars), return as-is
    if (!component.contains(' ') && !containsIllegalUriCharacters(component)) {
        return component
    }
    
    // Simple approach: replace spaces and let other characters be handled by URI constructor
    // More sophisticated approach would be to selectively encode only illegal characters
    return component.replace(" ", "%20")
        .replace("<", "%3C")
        .replace(">", "%3E")
        .replace("\"", "%22")
        .replace("{", "%7B")
        .replace("}", "%7D")
        .replace("|", "%7C")
        .replace("\\", "%5C")
        .replace("^", "%5E")
        .replace("`", "%60")
}

/**
 * Checks if a string contains characters that are illegal in URI components.
 */
private fun containsIllegalUriCharacters(s: String): Boolean {
    return s.any { c ->
        c == ' ' || c == '<' || c == '>' || c == '"' || c == '{' || c == '}' || 
        c == '|' || c == '\\' || c == '^' || c == '`'
    }
}

