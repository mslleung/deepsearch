package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.PageOperationException
import io.deepsearch.domain.exceptions.AllProxiesFailedException
import io.deepsearch.domain.exceptions.BotBlockingException
import io.deepsearch.domain.exceptions.NoProxiesAvailableException
import io.deepsearch.domain.models.entities.DomainBypassStrategy
import io.deepsearch.domain.models.valueobjects.BypassStrategy
import io.deepsearch.domain.proxy.IFreeProxyProvider
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.repositories.IDomainBypassStrategyRepository
import io.deepsearch.domain.services.IBotDetectionService
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interface for bypass strategy service.
 */
interface IBypassStrategyService {
    /**
     * Execute a block with a browser page, using adaptive bypass strategy.
     *
     * The bypass strategy only applies when proxyConfig is None (the default):
     * - Custom/Included proxies are used directly without any bypass logic
     * - None triggers the adaptive bypass: direct first, then free rotating proxy fallback
     *
     * @param url The URL to navigate to
     * @param proxyConfig The user's configured proxy (None applies bypass strategy)
     * @param block The block to execute with the browser page
     * @return The result of the block
     */
    suspend fun <T> withPageWithBypass(
        url: String,
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None,
        block: suspend (IBrowserPage) -> T
    ): T
}

/**
 * Service that orchestrates navigation with adaptive bypass strategy.
 * 
 * Key features:
 * - Lookup domain strategy from DB (default: DIRECT)
 * - For DIRECT: attempt direct connection; on block, update to FREE_ROTATING_PROXY and retry
 * - For FREE_ROTATING_PROXY: fan out to 3 proxies from different regions
 * - First successful result wins; cancel in-flight requests
 * - Track active connections to prevent duplicate proxy-domain pairs
 * - Update DB with success/failure metrics
 */
class BypassStrategyService(
    private val browserPool: IBrowserPool,
    private val domainBypassStrategyRepository: IDomainBypassStrategyRepository,
    private val freeProxyProvider: IFreeProxyProvider,
    private val botDetectionService: IBotDetectionService
) : IBypassStrategyService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Tracks active proxy connections per domain to prevent duplicates.
     */
    private val activeProxiesByDomain = ConcurrentHashMap<String, MutableSet<String>>()

    companion object {
        private const val FANOUT_COUNT = 3
    }

    override suspend fun <T> withPageWithBypass(
        url: String,
        proxyConfig: ProxyConfiguration,
        block: suspend (IBrowserPage) -> T
    ): T {
        // Custom and Included proxies bypass the adaptive strategy - use them directly
        if (proxyConfig is ProxyConfiguration.Custom || proxyConfig is ProxyConfiguration.Included) {
            logger.debug("Using user-configured proxy for {}: {}", url, proxyConfig::class.simpleName)
            return browserPool.withPage(proxyConfig) { page ->
                page.navigate(url)
                block(page)
            }
        }

        // For None (default), apply the adaptive bypass strategy
        val domain = DomainBypassStrategy.extractDomain(url)
            ?: throw IllegalArgumentException("Cannot extract domain from URL: $url")

        val strategy = domainBypassStrategyRepository.getOrCreate(domain)
        logger.debug("Using bypass strategy {} for domain {}", strategy.strategy, domain)

        return when (strategy.strategy) {
            BypassStrategy.DIRECT -> executeWithDirectStrategy(url, domain, block)
            BypassStrategy.FREE_ROTATING_PROXY -> executeWithProxyFanout(url, domain, block)
        }
    }

    /**
     * Execute with direct connection. If blocked, upgrade to proxy strategy and retry.
     */
    private suspend fun <T> executeWithDirectStrategy(
        url: String,
        domain: String,
        block: suspend (IBrowserPage) -> T
    ): T {
        return try {
            val result = browserPool.withPage(ProxyConfiguration.None) { page ->
                page.navigate(url)
                
                // Check for blocking after navigation
                val html = page.getFullHtml()
                botDetectionService.checkForBlocking(url, html)
                
                block(page)
            }
            
            // Record success
            domainBypassStrategyRepository.recordSuccess(domain)
            result
        } catch (e: BotBlockingException) {
            logger.info("Blocking detected for {} ({}), upgrading to proxy strategy", domain, e.errorCode)
            
            // Upgrade to proxy strategy
            domainBypassStrategyRepository.recordBlocked(domain)
            
            // Retry with proxy fanout
            executeWithProxyFanout(url, domain, block)
        } catch (e: PageOperationException) {
            // Check if it's a blocking-related error
            if (isBlockingError(e)) {
                logger.info("Page operation error indicates blocking for {}: {}", domain, e.message)
                domainBypassStrategyRepository.recordBlocked(domain)
                executeWithProxyFanout(url, domain, block)
            } else {
                throw e
            }
        }
    }

    /**
     * Execute with proxy fanout - try 3 proxies in parallel, first success wins.
     */
    private suspend fun <T> executeWithProxyFanout(
        url: String,
        domain: String,
        block: suspend (IBrowserPage) -> T
    ): T = coroutineScope {
        val proxies = freeProxyProvider.selectProxiesForDomain(domain, FANOUT_COUNT)
        
        if (proxies.isEmpty()) {
            logger.warn("No proxies available for domain {}", domain)
            throw NoProxiesAvailableException(url)
        }

        logger.debug("Fanout to {} proxies for {}: {}", proxies.size, domain, proxies)

        val activeSet = activeProxiesByDomain.getOrPut(domain) {
            ConcurrentHashMap.newKeySet()
        }

        // Track which proxies we're actually using (filter out already active ones)
        val usableProxies = proxies.filter { !activeSet.contains(it) }
        if (usableProxies.isEmpty()) {
            logger.warn("All selected proxies already in use for domain {}", domain)
            throw NoProxiesAvailableException(url)
        }

        // Mark proxies as active
        usableProxies.forEach { activeSet.add(it) }

        val failedProxies = mutableListOf<String>()
        val errors = ConcurrentHashMap<String, Throwable>()
        val successfulProxy = AtomicInteger(-1)

        try {
            // Launch parallel attempts
            val deferreds = usableProxies.mapIndexed { index, proxyUrl ->
                async {
                    try {
                        val result = executeWithProxy(url, proxyUrl, block)
                        successfulProxy.set(index)
                        Result.success(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.debug("Proxy {} failed for {}: {}", proxyUrl, url, e.message)
                        errors[proxyUrl] = e
                        failedProxies.add(proxyUrl)
                        
                        // Mark proxy as failed if it's a connection issue
                        if (isProxyConnectionError(e)) {
                            freeProxyProvider.markProxyFailed(domain, proxyUrl)
                        }
                        
                        Result.failure(e)
                    }
                }
            }

            // Wait for first success or all failures using select
            var result: T? = null
            var allFailed = false
            
            while (result == null && !allFailed) {
                val completedIndex = select<Int?> {
                    deferreds.forEachIndexed { index, deferred ->
                        if (deferred.isActive) {
                            deferred.onAwait { 
                                if (it.isSuccess) index else null 
                            }
                        }
                    }
                }
                
                if (completedIndex != null) {
                    // We have a winner
                    result = deferreds[completedIndex].await().getOrNull()
                    
                    // Cancel other in-flight requests
                    deferreds.forEachIndexed { index, deferred ->
                        if (index != completedIndex && deferred.isActive) {
                            deferred.cancel()
                        }
                    }
                } else {
                    // Check if all are completed (failed)
                    allFailed = deferreds.all { it.isCompleted }
                }
            }

            if (result != null) {
                // Record success
                domainBypassStrategyRepository.recordSuccess(domain)
                return@coroutineScope result
            }

            // All failed
            val firstError = errors.values.firstOrNull()
            logger.warn("All {} proxies failed for {}", usableProxies.size, url)
            throw AllProxiesFailedException(url, usableProxies.size, firstError)

        } finally {
            // Release all proxies for this domain
            usableProxies.forEach { activeSet.remove(it) }
            freeProxyProvider.releaseProxies(domain, usableProxies)
        }
    }

    /**
     * Execute navigation with a specific proxy.
     */
    private suspend fun <T> executeWithProxy(
        url: String,
        proxyUrl: String,
        block: suspend (IBrowserPage) -> T
    ): T {
        return browserPool.withPage(ProxyConfiguration.FreeRotating(proxyUrl)) { page ->
            page.navigate(url)
            
            // Check for blocking after navigation
            val html = page.getFullHtml()
            botDetectionService.checkForBlocking(url, html)
            
            block(page)
        }
    }

    private fun isBlockingError(e: PageOperationException): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("403") ||
                message.contains("blocked") ||
                message.contains("forbidden") ||
                message.contains("cloudflare") ||
                message.contains("captcha")
    }

    private fun isProxyConnectionError(e: Throwable): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("connection refused") ||
                message.contains("connection reset") ||
                message.contains("connection timed out") ||
                message.contains("proxy") ||
                message.contains("tunnel")
    }
}
