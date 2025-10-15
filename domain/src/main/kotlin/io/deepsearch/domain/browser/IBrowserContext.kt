package io.deepsearch.domain.browser

interface IBrowserContext {
    fun newPage(): IBrowserPage
    
    /**
     * Close this browser context and clean up resources.
     */
    fun close()
}