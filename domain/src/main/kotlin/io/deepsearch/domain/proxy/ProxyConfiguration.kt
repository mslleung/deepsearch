package io.deepsearch.domain.proxy

/**
 * Represents the user's proxy configuration choice.
 * This is selected by the user in the UI.
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
     * Premium Proxyrack residential proxy.
     * The actual proxy credentials are configured at the system level.
     */
    data object Premium : ProxyConfiguration()

    /**
     * Free rotating proxy from ProxyScrape pool.
     * When selected, the system will fanout to multiple proxies.
     */
    data object FreeRotating : ProxyConfiguration()

    companion object {
        /**
         * Create a ProxyConfiguration from a ProxyRule.
         */
        fun fromRule(rule: ProxyRule): ProxyConfiguration {
            return when (rule.proxyType) {
                ProxyType.NONE -> None
                ProxyType.CUSTOM -> Custom(rule.customProxyUrl!!)
                ProxyType.PREMIUM -> Premium
                ProxyType.FREE_ROTATING -> FreeRotating
            }
        }
    }
}
