package io.deepsearch.application.services

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.exceptions.AllProxiesFailedException
import io.deepsearch.domain.proxy.ProxyConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface IBrowserPageResolver {
    /**
     * Execute [block] with a browser page that has been navigated to [url] using
     * pre-fetched HTML content via CDP Fetch interception (no second server request).
     *
     * When [proxyConfig] resolves to multiple proxies, launches parallel attempts
     * and uses the first successful connection.
     */
    suspend fun <T> withPageForCachedHtml(
        url: String,
        cachedHtmlBody: ByteArray,
        proxyConfig: ProxyConfiguration,
        block: suspend (IBrowserPage) -> T
    ): T
}

class BrowserPageResolver(
    private val browserPool: IBrowserPool,
    private val proxyResolutionService: IProxyResolutionService
) : IBrowserPageResolver {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun <T> withPageForCachedHtml(
        url: String,
        cachedHtmlBody: ByteArray,
        proxyConfig: ProxyConfiguration,
        block: suspend (IBrowserPage) -> T
    ): T {
        val proxyUrls = proxyResolutionService.resolve(url, proxyConfig)
        return if (proxyUrls.size <= 1) {
            browserPool.withPage(proxyUrls.firstOrNull()) { page ->
                page.navigateWithCachedHtml(url, cachedHtmlBody)
                block(page)
            }
        } else {
            withProxyFanout(proxyUrls, url, cachedHtmlBody, block)
        }
    }

    private suspend fun <T> withProxyFanout(
        proxyUrls: List<String>,
        navigateUrl: String,
        cachedHtmlBody: ByteArray,
        block: suspend (IBrowserPage) -> T
    ): T = coroutineScope {
        logger.debug("Fanout to {} proxies for {} (with cached HTML)", proxyUrls.size, navigateUrl)

        val result = CompletableDeferred<T>()
        val winnerSelected = AtomicBoolean(false)
        val failureCount = AtomicInteger(0)

        val jobs = proxyUrls.map { proxyUrl ->
            launch {
                try {
                    browserPool.withPage(proxyUrl) { page ->
                        page.navigateWithCachedHtml(navigateUrl, cachedHtmlBody)

                        if (winnerSelected.compareAndSet(false, true)) {
                            val output = block(page)
                            result.complete(output)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("Proxy {} failed: {}", proxyUrl, e.message)
                    if (failureCount.incrementAndGet() == proxyUrls.size) {
                        result.completeExceptionally(
                            AllProxiesFailedException(proxyUrls.first(), proxyUrls.size, e)
                        )
                    }
                }
            }
        }

        try {
            result.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}
