package io.deepsearch.domain.proxy

/**
 * Represents the resolved proxy configuration for a specific request.
 * This is the result of matching a URL against user's proxy rules.
 */
sealed class ProxyConfiguration {
    /**
     * No proxy - direct connection.
     */
    data object None : ProxyConfiguration()

    /**
     * Custom proxy provided by the user.
     *
     * @property proxyUrl The proxy server URL (e.g., "socks5://1.2.3.4:1080")
     */
    data class Custom(val proxyUrl: String) : ProxyConfiguration()

    /**
     * Included Proxyrack residential proxy.
     * The actual proxy credentials are configured at the system level.
     */
    data object Included : ProxyConfiguration()

    companion object {
        /**
         * Create a ProxyConfiguration from a ProxyRule.
         */
        fun fromRule(rule: ProxyRule): ProxyConfiguration {
            return when (rule.proxyType) {
                ProxyType.NONE -> None
                ProxyType.CUSTOM -> Custom(rule.customProxyUrl!!)
                ProxyType.INCLUDED -> Included
            }
        }
    }
}

