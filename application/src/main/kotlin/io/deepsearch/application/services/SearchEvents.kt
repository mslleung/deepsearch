package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.QuerySessionId

/**
 * Summary of the feedback loop execution for logging and debugging.
 */
data class FeedbackLoopReport(
    val totalIterations: Int,
    val followUpQueries: List<String>,
    val sourcesPerIteration: List<Int>,
    val finalStatus: String,
    val totalSynthesisCalls: Int,
    val durationMs: Long
)

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
     * Emitted when a URL starts being processed (before crawl/cache lookup).
     */
    data class UrlProcessingStarted(
        override val sessionId: QuerySessionId,
        val url: String,
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
        val isPreview: Boolean = false,  // true for simple text extraction, false for full markdown
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when a URL's content is upgraded from preview (simple text) to full markdown.
     * This allows the frontend to update the source display with complete content.
     */
    data class UrlContentUpgraded(
        override val sessionId: QuerySessionId,
        val url: String,
        val title: String? = null,
        val description: String? = null,
        val markdownLength: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when sources have been evaluated and relevance is determined.
     */
    data class SourcesEvaluated(
        override val sessionId: QuerySessionId,
        val processedUrlCount: Int,
        val relevantCount: Int,
        val isGoodEnough: Boolean,
        val reason: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when an answer chunk is generated during streaming answer synthesis.
     * Multiple chunks are emitted as the answer is progressively generated.
     */
    data class AnswerChunk(
        override val sessionId: QuerySessionId,
        val chunk: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted when the feedback loop triggers a follow-up search based on synthesis agent's request.
     */
    data class FollowUpSearchStarted(
        override val sessionId: QuerySessionId,
        val followUpQueries: List<String>,
        val whatsMissing: String?,
        val iterationNumber: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SearchEvent()

    /**
     * Emitted after each synthesis iteration to report progress.
     */
    data class SynthesisIteration(
        override val sessionId: QuerySessionId,
        val iterationNumber: Int,
        val status: String,  // "COMPLETE" or "NEEDS_MORE_SOURCES"
        val sourceCount: Int,
        val followUpQueries: List<String>,
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
        val sessionDetail: QuerySessionDetail,
        /**
         * Image IDs referenced in the answer (format: "img-xxx").
         * These can be used to fetch image bytes from the repository.
         */
        val imageIds: List<String> = emptyList(),
        /**
         * Feedback loop report data for logging and debugging.
         */
        val loopReport: FeedbackLoopReport? = null
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

