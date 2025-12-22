package io.deepsearch.application.services

import io.deepsearch.domain.config.ProxyrackHttpConfig
import io.deepsearch.domain.proxy.IFreeProxyProvider
import io.deepsearch.domain.proxy.ProxyConfiguration
import org.slf4j.LoggerFactory

/**
 * Interface for resolving proxy configurations to actual proxy URLs.
 */
interface IProxyResolutionService {
    /**
     * Resolve a proxy configuration to actual proxy URL(s).
     * 
     * @param url The URL being accessed (for domain-based selection in FreeRotating)
     * @param config The user's proxy configuration choice
     * @return List of proxy URLs. Empty list means direct connection.
     *         Single element for Custom/Premium, multiple for FreeRotating fanout.
     */
    suspend fun resolve(url: String, config: ProxyConfiguration): List<String>
}

/**
 * Service that resolves proxy configurations to actual proxy URLs.
 * 
 * Handles the mapping from user's proxy choice to concrete proxy URLs:
 * - None -> empty list (direct connection)
 * - Custom -> single-element list with user's custom proxy URL
 * - Premium -> single-element list with Proxyrack residential proxy URL
 * - FreeRotating -> multiple proxy URLs for fanout
 */
class ProxyResolutionService(
    private val proxyrackConfig: ProxyrackHttpConfig,
    private val freeProxyProvider: IFreeProxyProvider
) : IProxyResolutionService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val FANOUT_COUNT = 3
    }

    override suspend fun resolve(url: String, config: ProxyConfiguration): List<String> {
        return when (config) {
            is ProxyConfiguration.None -> emptyList()
            is ProxyConfiguration.Custom -> listOf(config.proxyUrl)
            is ProxyConfiguration.Premium -> listOf(proxyrackConfig.toProxyUrl())
            is ProxyConfiguration.FreeRotating -> {
                val domain = extractDomain(url) ?: "default"
                val proxies = freeProxyProvider.selectProxiesForDomain(domain, FANOUT_COUNT)
                logger.debug("Resolved {} proxies for fanout to domain {}", proxies.size, domain)
                proxies
            }
        }
    }

    private fun extractDomain(url: String): String? {
        return try {
            val withProtocol = if (url.contains("://")) url else "https://$url"
            java.net.URI(withProtocol).host?.lowercase()
        } catch (e: Exception) {
            logger.warn("Failed to extract domain from URL: {}", url)
            null
        }
    }
}
