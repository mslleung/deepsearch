package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.QuerySessionId

/**
 * Events emitted during search execution to provide real-time progress updates.
 * 
 * These events are application-layer domain concepts using proper value objects.
 * They are mapped to SearchEventDto in the presentation layer for serialization over SSE.
 */
sealed class SearchEvent {
    abstract val sessionId: QuerySessionId
    abstract val timestampMs: Long

    /**
     * Emitted when a search session is created and search begins.
     */
    data class SessionCreated(
        override val sessionId: QuerySessionId,
        val query: String,
        val url: String,
        val mode: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when a URL is processed (cached hit, fresh crawl, or failure).
     */
    data class UrlProcessed(
        override val sessionId: QuerySessionId,
        val url: String,
        val accessType: String,  // "CACHED", "UNCACHED", "FAILED"
        val title: String? = null,
        val description: String? = null,
        val markdownLength: Int? = null,
        val errorMessage: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when the source shortlist is updated after processing a batch of URLs.
     */
    data class ShortlistUpdated(
        override val sessionId: QuerySessionId,
        val processedUrlCount: Int,
        val shortlistedCount: Int,
        val isGoodEnough: Boolean,
        val reason: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when the search session completes successfully with an answer.
     * Contains the full session detail for the presentation layer to serialize.
     */
    data class SessionCompleted(
        override val sessionId: QuerySessionId,
        val finishReason: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        /**
         * The full session detail including URL accesses and cached webpages.
         * The presentation layer maps this to DTOs for serialization.
         */
        val sessionDetail: QuerySessionDetail
    ) : SearchEvent()

    /**
     * Emitted when an error occurs during search execution.
     * Note: For pre-session errors, use QuerySessionId.empty()
     */
    data class SessionError(
        override val sessionId: QuerySessionId,
        val errorType: String,
        val errorMessage: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()
}

