package io.deepsearch.domain.browser

/**
 * Represents a browser instance created from a runtime.
 * 
 * Each browser is isolated and manages thread-safe access to the Playwright API
 * through a per-browser mutex, enabling multiple browsers from the same runtime
 * to operate concurrently while ensuring each browser's operations are serialized.
 * 
 * Browsers are typically created per-link during web scraping to allow parallel
 * processing of multiple pages.
 */
interface IBrowser {
    /**
     * Create a new context in this browser.
     * Contexts provide isolation for cookies, storage, etc.
     */
    suspend fun createContext(): IBrowserContext

    /**
     * Close this browser and clean up resources.
     */
    suspend fun close()
}