package io.deepsearch.domain.browser

interface IBrowser {
    fun createContext(): IBrowserContext

    /**
     * Close everything and clean up resources in this browser.
     */
    suspend fun close()
}