package io.deepsearch.domain.browser

interface IBrowserContext {
    suspend fun newPage(): IBrowserPage
    
    /**
     * Close this browser context and clean up resources.
     */
    suspend fun close()
}