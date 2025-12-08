package io.deepsearch.domain.browser

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
     */
    suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T
}
