package io.deepsearch.domain.browser

/**
 * Represents a browser context - an isolated browsing session within a browser.
 * 
 * Contexts provide isolation (cookies, storage, etc.) and can have multiple pages.
 */
interface IBrowserContext {
    /**
     * Create a new page in this context.
     */
    suspend fun newPage(): IBrowserPage
    
    /**
     * Close this browser context and clean up resources.
     */
    suspend fun close()
}

