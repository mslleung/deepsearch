package io.deepsearch.domain.browser

/**
 * Represents a browser runtime (e.g., a Playwright Chromium instance).
 * 
 * Runtimes are expensive to create and are pooled for reuse across queries.
 * Each runtime can spawn multiple browsers for concurrent link processing.
 */
interface IBrowserRuntime {
    /**
     * Create a new browser instance from this runtime.
     * Each browser is isolated and has its own mutex for thread-safe Playwright API access.
     */
    suspend fun createBrowser(): IBrowser

    /**
     * Close the runtime and clean up all resources.
     */
    suspend fun close()
}

