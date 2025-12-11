package io.deepsearch.domain.browser

import io.deepsearch.domain.proxy.ProxyConfiguration

/**
 * Pool for acquiring browser pages with shared browser processes and contexts.
 *
 * Implementation is provided by RemoteBrowserPool which connects to
 * the deepsearch-browser service.
 */
interface IBrowserPool {
    /**
     * Acquire a browser page, execute the block, and automatically close the page.
     * The page is released back to the pool after the block completes.
     * 
     * @param proxyConfig The proxy configuration for this page (default: no proxy)
     * @param block The code block to execute with the browser page
     */
    suspend fun <T> withPage(
        proxyConfig: ProxyConfiguration = ProxyConfiguration.None, 
        block: suspend (IBrowserPage) -> T
    ): T
}
