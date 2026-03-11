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
    URL_LINKS_DISCOVERED,
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
 * Type-safe sealed class hierarchy for search flow events.
 * 
 * Each event type has strongly-typed fields instead of a generic metadata map.
 * This provides compile-time safety and eliminates runtime casting errors.
 *
 * @property id Database-generated ID (0 for unsaved events)
 * @property sessionId The query session this event belongs to
 * @property timestampMs Epoch milliseconds when the event occurred
 * @property createdAt Instant when the event was created
 */
@OptIn(ExperimentalTime::class)
sealed class SearchFlowEvent {
    abstract val id: Long
    abstract val sessionId: QuerySessionId
    abstract val timestampMs: Long
    abstract val createdAt: Instant
    
    /** Returns the event type enum for this event */
    abstract val eventType: SearchFlowEventType
    
    /** Create a copy with a new ID (used after database insert) */
    abstract fun withId(newId: Long): SearchFlowEvent

    // ============ Session Lifecycle Events ============

    data class SessionStarted(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val query: String,
        val url: String,
        val mode: String = "live-crawling"
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SESSION_STARTED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class SessionCompleted(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SESSION_COMPLETED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class SessionTimeout(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SESSION_TIMEOUT
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class SessionError(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val errorType: String,
        val errorMessage: String,
        val errorCategory: String? = null,
        val affectedUrl: String? = null,
        val technicalDetails: String? = null
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SESSION_ERROR
        override fun withId(newId: Long) = copy(id = newId)
    }

    // ============ Query Processing Events ============

    data class QueryProcessingStarted(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.QUERY_PROCESSING_STARTED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class QueryProcessingComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.QUERY_PROCESSING_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    // ============ Discovery Phase Events ============

    data class DiscoveryStarted(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.DISCOVERY_STARTED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class DiscoverySerpComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val query: String,
        val linksFound: Int,
        val durationMs: Long
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.DISCOVERY_SERP_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class DiscoveryHybridComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.DISCOVERY_HYBRID_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class DiscoveryKgComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.DISCOVERY_KG_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class DiscoveryFileSearchComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.DISCOVERY_FILE_SEARCH_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    // ============ URL Processing Events ============

    data class UrlProcessingStarted(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val url: String
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.URL_PROCESSING_STARTED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class UrlLinksDiscovered(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val url: String
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.URL_LINKS_DISCOVERED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class UrlMarkdownComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val url: String,
        val title: String? = null,
        val description: String? = null,
        val markdownLength: Int,
        val accessType: String,
        val wasCached: Boolean
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.URL_MARKDOWN_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class UrlProcessingFailed(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val url: String,
        val errorMessage: String
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.URL_PROCESSING_FAILED
        override fun withId(newId: Long) = copy(id = newId)
    }

    // ============ Evaluation Events ============

    data class SourcesEvaluated(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val processedUrlCount: Int,
        val relevantCount: Int,
        val isGoodEnough: Boolean,
        val reason: String? = null
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SOURCES_EVALUATED
        override fun withId(newId: Long) = copy(id = newId)
    }

    // ============ Synthesis Events ============

    data class SynthesisStarted(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now()
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SYNTHESIS_STARTED
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class SynthesisComplete(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val iterationNumber: Int,
        val sourceCount: Int,
        val status: String,
        val followUpQueries: List<String>
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.SYNTHESIS_COMPLETE
        override fun withId(newId: Long) = copy(id = newId)
    }

    data class AnswerChunk(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val chunk: String
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.ANSWER_CHUNK
        override fun withId(newId: Long) = copy(id = newId)
    }

    // ============ Follow-up Events ============

    data class FollowUpQueryGenerated(
        override val id: Long = 0,
        override val sessionId: QuerySessionId,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val createdAt: Instant = Clock.System.now(),
        val followUpQueries: List<String>,
        val whatsMissing: String? = null,
        val iterationNumber: Int
    ) : SearchFlowEvent() {
        override val eventType = SearchFlowEventType.FOLLOW_UP_QUERY_GENERATED
        override fun withId(newId: Long) = copy(id = newId)
    }
}
