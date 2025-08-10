package io.deepsearch.domain.models.valueobjects

interface IBrowser : AutoCloseable {
    fun createContext(): IBrowserContext

    /**
     * Close everything and clean up resources in this browser.
     */
    override fun close()
}