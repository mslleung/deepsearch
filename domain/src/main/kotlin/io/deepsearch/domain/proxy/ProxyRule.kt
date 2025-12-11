package io.deepsearch.domain.proxy

import io.deepsearch.domain.models.valueobjects.ProxyRuleId
import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Domain entity representing a user's proxy configuration rule for specific URL patterns.
 *
 * @property id The unique identifier for this rule (null if not yet persisted)
 * @property userId The user who owns this rule
 * @property urlPattern Domain pattern for matching URLs (e.g., "example.com", "*.example.com")
 * @property proxyType The type of proxy to use for matching URLs
 * @property customProxyUrl The custom proxy server URL (required when proxyType is CUSTOM)
 * @property createdAt When this rule was created
 * @property updatedAt When this rule was last modified
 */
@OptIn(ExperimentalTime::class)
class ProxyRule(
    var id: ProxyRuleId? = null,
    val userId: UserId,
    var urlPattern: String,
    var proxyType: ProxyType,
    var customProxyUrl: String? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now()
) {
    init {
        validate()
    }

    private fun validate() {
        require(urlPattern.isNotBlank()) { "URL pattern cannot be blank" }
        require(urlPattern.length <= 255) { "URL pattern must be 255 characters or less" }

        // Validate URL pattern format (simple domain or wildcard pattern)
        val validPattern = urlPattern.matches(Regex("^(\\*\\.)?[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$"))
        require(validPattern) { "Invalid URL pattern format: $urlPattern" }

        if (proxyType == ProxyType.CUSTOM) {
            requireNotNull(customProxyUrl) { "Custom proxy URL is required when proxy type is CUSTOM" }
            require(customProxyUrl!!.isNotBlank()) { "Custom proxy URL cannot be blank" }
            validateCustomProxyUrl(customProxyUrl!!)
        } else {
            // Clear custom proxy URL if type is not CUSTOM
            customProxyUrl = null
        }
    }

    private fun validateCustomProxyUrl(url: String) {
        // Validate proxy URL format: http://host:port, https://host:port, socks5://host:port
        val proxyUrlPattern = Regex("^(https?|socks5)://([a-zA-Z0-9.-]+|\\d{1,3}(\\.\\d{1,3}){3})(:\\d{1,5})?$")
        require(proxyUrlPattern.matches(url)) {
            "Invalid proxy URL format. Expected: http://host:port, https://host:port, or socks5://host:port"
        }
    }

    /**
     * Check if this rule matches the given URL.
     *
     * @param url The URL to check against this rule's pattern
     * @return true if the URL matches this rule's pattern
     */
    fun matches(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return matchesPattern(host)
    }

    private fun extractHost(url: String): String? {
        return try {
            val regex = Regex("^https?://([^/:]+)")
            regex.find(url)?.groupValues?.get(1)?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    private fun matchesPattern(host: String): Boolean {
        val pattern = urlPattern.lowercase()
        val normalizedHost = host.lowercase()

        return if (pattern.startsWith("*.")) {
            // Wildcard pattern: *.example.com matches sub.example.com and example.com
            val baseDomain = pattern.substring(2)
            normalizedHost == baseDomain || normalizedHost.endsWith(".$baseDomain")
        } else {
            // Exact match
            normalizedHost == pattern
        }
    }

    /**
     * Update the rule configuration.
     */
    fun update(
        urlPattern: String? = null,
        proxyType: ProxyType? = null,
        customProxyUrl: String? = null,
        updatedAt: Instant = Clock.System.now()
    ) {
        urlPattern?.let { this.urlPattern = it }
        proxyType?.let { this.proxyType = it }
        
        // Handle customProxyUrl based on proxyType
        if (proxyType == ProxyType.CUSTOM || this.proxyType == ProxyType.CUSTOM) {
            this.customProxyUrl = customProxyUrl
        }
        
        this.updatedAt = updatedAt
        validate()
    }
}

