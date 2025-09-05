package io.deepsearch.domain.browser

interface IBrowser : AutoCloseable {
    fun createContext(): IBrowserContext

    /**
     * Close everything and clean up resources in this browser.
     */
    override suspend fun close()
}