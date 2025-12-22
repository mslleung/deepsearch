package io.deepsearch.domain.proxy

import org.slf4j.LoggerFactory

/**
 * Interface for free proxy provider.
 * Provides proxies for bypass strategy fanout.
 */
interface IFreeProxyProvider {
    /**
     * Select proxies for a domain, ensuring no duplicates with current active connections.
     * 
     * @param domain The domain being accessed
     * @param count Number of proxies to select
     * @return List of proxy URLs
     */
    suspend fun selectProxiesForDomain(domain: String, count: Int): List<String>

    /**
     * Release proxies after use.
     */
    suspend fun releaseProxies(domain: String, proxyUrls: List<String>)

    /**
     * Mark a proxy as failed.
     */
    suspend fun markProxyFailed(domain: String, proxyUrl: String)

    /**
     * Check if proxies are available.
     */
    suspend fun hasAvailableProxies(): Boolean
}

/**
 * Local implementation of IFreeProxyProvider that manages proxies directly.
 * 
 * Uses FreeProxyPool to:
 * - Select proxies for a domain with country-based distribution
 * - Mark proxies as failed
 * - Check proxy availability
 */
class FreeProxyProvider(
    private val proxyPool: FreeProxyPool
) : IFreeProxyProvider {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun selectProxiesForDomain(domain: String, count: Int): List<String> {
        val proxies = proxyPool.selectProxiesForFanout(domain, count)
        logger.debug("Selected {} proxies for domain {}", proxies.size, domain)
        return proxies.map { it.url }
    }

    override suspend fun releaseProxies(domain: String, proxyUrls: List<String>) {
        proxyUrls.forEach { proxyUrl ->
            proxyPool.releaseProxy(domain, proxyUrl)
        }
        logger.debug("Released {} proxies for domain {}", proxyUrls.size, domain)
    }

    override suspend fun markProxyFailed(domain: String, proxyUrl: String) {
        proxyPool.markProxyFailedAndRelease(domain, proxyUrl)
        logger.debug("Marked proxy {} as failed for domain {}", proxyUrl, domain)
    }

    override suspend fun hasAvailableProxies(): Boolean {
        return proxyPool.hasAvailableProxies()
    }
}

