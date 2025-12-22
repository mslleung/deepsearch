package io.deepsearch.domain.proxy

/**
 * Types of proxy configurations available for URL routing.
 */
enum class ProxyType {
    /**
     * No proxy - direct connection (current default behavior).
     */
    NONE,

    /**
     * Custom proxy - user supplies their own HTTP or SOCKS5 proxy server.
     */
    CUSTOM,

    /**
     * Included proxy - uses the system-configured Proxyrack residential proxy.
     */
    INCLUDED,

    /**
     * Free rotating proxy - uses ProxyScrape pool with fanout strategy.
     */
    FREE_ROTATING
}
