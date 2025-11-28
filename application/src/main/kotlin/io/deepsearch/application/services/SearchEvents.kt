package io.deepsearch.application.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Events emitted during search execution to provide real-time progress updates.
 * Follows the same pattern as PeriodicIndexEvent for SSE streaming.
 */
@Serializable
sealed class SearchEvent {
    abstract val sessionId: String
    abstract val timestampMs: Long

    /**
     * Emitted when a search session is created and search begins.
     */
    @Serializable
    @SerialName("session_created")
    data class SessionCreated(
        override val sessionId: String,
        val query: String,
        val url: String,
        val mode: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when a URL is processed (cached hit, fresh crawl, or failure).
     */
    @Serializable
    @SerialName("url_processed")
    data class UrlProcessed(
        override val sessionId: String,
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
    @Serializable
    @SerialName("shortlist_updated")
    data class ShortlistUpdated(
        override val sessionId: String,
        val processedUrlCount: Int,
        val shortlistedCount: Int,
        val isGoodEnough: Boolean,
        val reason: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when the search session completes successfully with an answer.
     */
    @Serializable
    @SerialName("session_completed")
    data class SessionCompleted(
        override val sessionId: String,
        val answer: String?,
        val finishReason: String,
        val durationMs: Long?,
        val answerSourceCount: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when an error occurs during search execution.
     */
    @Serializable
    @SerialName("session_error")
    data class SessionError(
        override val sessionId: String,
        val errorType: String,
        val errorMessage: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()
}

