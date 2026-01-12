package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Event types for all search flow operations.
 * These events are emitted by the orchestrator, persisted for timeline visualization,
 * and mapped to SearchEvent for SSE streaming.
 */
enum class SearchFlowEventType {
    // Session lifecycle (maps to SearchEvent.SessionCreated/Completed/Error)
    SESSION_STARTED,
    SESSION_COMPLETED,
    SESSION_TIMEOUT,
    SESSION_ERROR,

    // Query processing
    QUERY_PROCESSING_STARTED,
    QUERY_PROCESSING_COMPLETE,

    // Discovery phase (timeline-only, no SSE equivalent)
    DISCOVERY_STARTED,
    DISCOVERY_SERP_COMPLETE,
    DISCOVERY_HYBRID_COMPLETE,
    DISCOVERY_KG_COMPLETE,
    DISCOVERY_FILE_SEARCH_COMPLETE,

    // URL processing (maps to SearchEvent.UrlProcessingStarted/UrlProcessed)
    URL_PROCESSING_STARTED,
    URL_HTML_PREVIEW_READY,
    URL_LINK_DISCOVERY_COMPLETE,
    URL_MARKDOWN_COMPLETE,
    URL_PROCESSING_FAILED,

    // Evaluation (maps to SearchEvent.SourcesEvaluated)
    SOURCES_EVALUATED,

    // Synthesis (maps to SearchEvent.SynthesisIteration/AnswerChunk)
    SYNTHESIS_STARTED,
    SYNTHESIS_COMPLETE,
    ANSWER_CHUNK,

    // Follow-up (maps to SearchEvent.FollowUpSearchStarted)
    FOLLOW_UP_QUERY_GENERATED
}

/**
 * Unified event type for all search flow operations.
 * 
 * Emitted by orchestrator, persisted for timeline visualization, and mapped to SearchEvent for SSE.
 * This provides a single source of truth for all events in the search flow.
 *
 * @property id Database-generated ID (0 for unsaved events)
 * @property sessionId The query session this event belongs to
 * @property eventType The type of event
 * @property timestampMs Epoch milliseconds when the event occurred
 * @property durationMs Duration of the operation (for completed events with timing)
 * @property url URL associated with this event (for URL processing events)
 * @property query Query string associated with this event (for discovery events)
 * @property title Page title (for URL events)
 * @property description Page description (for URL events)
 * @property metadata Flexible key-value data for event-specific information
 */
@OptIn(ExperimentalTime::class)
data class SearchFlowEvent(
    val id: Long = 0,
    val sessionId: QuerySessionId,
    val eventType: SearchFlowEventType,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long? = null,

    // Context fields (populated based on event type)
    val url: String? = null,
    val query: String? = null,
    val title: String? = null,
    val description: String? = null,

    // Flexible metadata for event-specific data
    // Examples: "mode" -> "live-crawling", "accessType" -> "CACHED", "markdownLength" -> 1234
    val metadata: Map<String, Any> = emptyMap(),
    
    val createdAt: Instant = Clock.System.now()
) {
    companion object {
        /**
         * Create a SESSION_STARTED event
         */
        fun sessionStarted(
            sessionId: QuerySessionId,
            query: String,
            url: String,
            mode: String = "live-crawling"
        ) = SearchFlowEvent(
            sessionId = sessionId,
            eventType = SearchFlowEventType.SESSION_STARTED,
            query = query,
            url = url,
            metadata = mapOf("mode" to mode)
        )

        /**
         * Create a URL_PROCESSING_STARTED event
         */
        fun urlProcessingStarted(sessionId: QuerySessionId, url: String) = SearchFlowEvent(
            sessionId = sessionId,
            eventType = SearchFlowEventType.URL_PROCESSING_STARTED,
            url = url
        )

        /**
         * Create a URL_MARKDOWN_COMPLETE event
         */
        fun urlMarkdownComplete(
            sessionId: QuerySessionId,
            url: String,
            title: String?,
            description: String?,
            markdownLength: Int,
            accessType: String,
            wasCached: Boolean
        ) = SearchFlowEvent(
            sessionId = sessionId,
            eventType = SearchFlowEventType.URL_MARKDOWN_COMPLETE,
            url = url,
            title = title,
            description = description,
            metadata = mapOf(
                "markdownLength" to markdownLength,
                "accessType" to accessType,
                "wasCached" to wasCached
            )
        )

        /**
         * Create a DISCOVERY_SERP_COMPLETE event
         */
        fun discoverySerperComplete(
            sessionId: QuerySessionId,
            query: String,
            linksFound: Int,
            durationMs: Long
        ) = SearchFlowEvent(
            sessionId = sessionId,
            eventType = SearchFlowEventType.DISCOVERY_SERP_COMPLETE,
            query = query,
            durationMs = durationMs,
            metadata = mapOf("linksFound" to linksFound)
        )

        /**
         * Create a SYNTHESIS_COMPLETE event
         */
        fun synthesisComplete(
            sessionId: QuerySessionId,
            iterationNumber: Int,
            sourceCount: Int,
            status: String,
            followUpQueries: List<String>
        ) = SearchFlowEvent(
            sessionId = sessionId,
            eventType = SearchFlowEventType.SYNTHESIS_COMPLETE,
            metadata = mapOf(
                "iterationNumber" to iterationNumber,
                "sourceCount" to sourceCount,
                "status" to status,
                "followUpQueries" to followUpQueries
            )
        )
    }
}
