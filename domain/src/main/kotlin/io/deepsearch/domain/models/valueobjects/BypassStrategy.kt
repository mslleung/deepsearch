package io.deepsearch.domain.models.valueobjects

/**
 * Enum representing the bypass strategy for accessing a domain.
 * 
 * The strategy is learned automatically based on access patterns:
 * - New domains start with DIRECT
 * - If blocking is detected, the domain is upgraded to FREE_ROTATING_PROXY
 */
enum class BypassStrategy {
    /**
     * Direct connection without any proxy.
     * This is the default strategy for new domains.
     */
    DIRECT,

    /**
     * Use free rotating proxies from ProxyScrape.
     * This strategy is used when direct access is blocked.
     */
    FREE_ROTATING_PROXY
}

