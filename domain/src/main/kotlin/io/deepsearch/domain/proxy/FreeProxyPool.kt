package io.deepsearch.domain.proxy

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages free proxy selection with country-based distribution and domain deduplication.
 *
 * Key features:
 * - Tracks active connections per domain to prevent duplicate proxy usage
 * - Selects proxies from different countries for fanout (geographic diversity)
 * - Removes proxies that fail to connect
 * - Thread-safe for concurrent access
 */
class FreeProxyPool(
    private val proxySyncService: FreeProxySyncService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Tracks active proxy connections per domain.
     * Key: domain, Value: set of proxy URLs currently in use for that domain
     */
    private val activeConnectionsByDomain = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Lock for proxy selection to ensure thread-safe country distribution.
     */
    private val selectionLock = ReentrantLock()

    /**
     * Select multiple proxies from different countries for fanout.
     * Ensures no duplicate proxies are used for the same domain concurrently.
     *
     * @param domain The domain being accessed
     * @param count Number of proxies to select (default: 3)
     * @return List of selected proxies (may be fewer than requested if not enough available)
     */
    fun selectProxiesForFanout(domain: String, count: Int = 3): List<FreeProxy> {
        return selectionLock.withLock {
            val activeForDomain = activeConnectionsByDomain.getOrPut(domain) {
                ConcurrentHashMap.newKeySet()
            }

            val availableCountries = proxySyncService.getAvailableCountries().toMutableList()
            val selectedProxies = mutableListOf<FreeProxy>()

            // Try to select one proxy from each country until we have enough
            while (selectedProxies.size < count && availableCountries.isNotEmpty()) {
                // Shuffle countries to distribute load
                availableCountries.shuffle()

                for (country in availableCountries.toList()) {
                    if (selectedProxies.size >= count) break

                    val proxy = selectProxyFromCountry(country, activeForDomain)
                    if (proxy != null) {
                        selectedProxies.add(proxy)
                        activeForDomain.add(proxy.url)
                        logger.debug("Selected proxy {} from country {} for domain {}", 
                            proxy.url, country, domain)
                    } else {
                        // No available proxy in this country, remove it
                        availableCountries.remove(country)
                    }
                }
            }

            if (selectedProxies.size < count) {
                logger.warn(
                    "Could only select {} of {} requested proxies for domain {} (available: {})",
                    selectedProxies.size, count, domain, proxySyncService.availableProxyCount
                )
            }

            selectedProxies
        }
    }

    /**
     * Select a single proxy from a specific country that is not already in use for the domain.
     */
    private fun selectProxyFromCountry(country: String, activeForDomain: Set<String>): FreeProxy? {
        val countryProxies = proxySyncService.getProxiesForCountry(country)
            .filter { !activeForDomain.contains(it.url) }

        if (countryProxies.isEmpty()) return null

        // Select randomly for load distribution
        return countryProxies.random()
    }

    /**
     * Release a proxy connection for a domain.
     * Call this when navigation completes (success or failure).
     *
     * @param domain The domain that was accessed
     * @param proxy The proxy that was used
     */
    fun releaseProxy(domain: String, proxy: FreeProxy) {
        releaseProxy(domain, proxy.url)
    }

    /**
     * Release a proxy connection by URL.
     */
    fun releaseProxy(domain: String, proxyUrl: String) {
        activeConnectionsByDomain[domain]?.remove(proxyUrl)
        logger.debug("Released proxy {} for domain {}", proxyUrl, domain)
    }

    /**
     * Mark a proxy as failed and release it.
     * The proxy will not be selected again until the next sync.
     *
     * @param domain The domain that was accessed
     * @param proxy The proxy that failed
     */
    fun markProxyFailedAndRelease(domain: String, proxy: FreeProxy) {
        markProxyFailedAndRelease(domain, proxy.url)
    }

    /**
     * Mark a proxy as failed by URL and release it.
     */
    fun markProxyFailedAndRelease(domain: String, proxyUrl: String) {
        releaseProxy(domain, proxyUrl)
        proxySyncService.markProxyFailed(proxyUrl)
        logger.info("Marked proxy {} as failed for domain {}", proxyUrl, domain)
    }

    /**
     * Get the count of active connections for a domain.
     */
    fun getActiveConnectionCount(domain: String): Int {
        return activeConnectionsByDomain[domain]?.size ?: 0
    }

    /**
     * Get total active connections across all domains.
     */
    fun getTotalActiveConnections(): Int {
        return activeConnectionsByDomain.values.sumOf { it.size }
    }

    /**
     * Check if there are any proxies available (not all failed).
     */
    fun hasAvailableProxies(): Boolean {
        return proxySyncService.availableProxyCount > 0
    }

    /**
     * Get pool status for monitoring.
     */
    fun getPoolStatus(): PoolStatus {
        return PoolStatus(
            syncStatus = proxySyncService.getSyncStatus(),
            totalActiveConnections = getTotalActiveConnections(),
            domainsWithActiveConnections = activeConnectionsByDomain.keys.size
        )
    }

    data class PoolStatus(
        val syncStatus: FreeProxySyncService.SyncStatus,
        val totalActiveConnections: Int,
        val domainsWithActiveConnections: Int
    )
}

