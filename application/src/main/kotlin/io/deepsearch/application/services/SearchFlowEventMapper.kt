package io.deepsearch.application.services

import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.entities.SearchFlowEventType

/**
 * Interface for SearchFlowEventMapper.
 * Maps SearchFlowEvent (unified timeline events) to SearchEvent (SSE streaming events).
 */
interface ISearchFlowEventMapper {
    /**
     * Maps a SearchFlowEvent to its corresponding SearchEvent for SSE streaming.
     * 
     * @param event The unified flow event from the orchestrator
     * @return The mapped SSE event, or null if this event type doesn't have an SSE equivalent
     */
    fun toStreamingSearchEndpointEvent(event: SearchFlowEvent): SearchEvent?

    /**
     * Check if a SearchFlowEventType should be sent to the streaming search endpoint.
     * Useful for filtering which events to send over SSE.
     */
    fun isStreamingSearchEndpointEvent(eventType: SearchFlowEventType): Boolean
}

/**
 * Maps SearchFlowEvent (unified timeline events) to SearchEvent (SSE streaming events).
 * 
 * This mapper is the bridge between the internal event system (SearchFlowEvent) and
 * the external SSE API (SearchEvent). Not all SearchFlowEvents have an SSE equivalent -
 * some events are only used for timeline visualization and debugging.
 * 
 * The mapping ensures:
 * 1. Backwards compatibility with existing frontend SSE handlers
 * 2. Timeline events can be persisted for debugging without affecting the SSE stream
 * 3. The orchestrator only needs to emit one type of event
 */
class SearchFlowEventMapper : ISearchFlowEventMapper {

    @Suppress("UNCHECKED_CAST")
    override fun toStreamingSearchEndpointEvent(event: SearchFlowEvent): SearchEvent? = when (event.eventType) {
        SearchFlowEventType.SESSION_STARTED -> SearchEvent.SessionCreated(
            sessionId = event.sessionId,
            query = event.query ?: "",
            url = event.url ?: "",
            mode = event.metadata["mode"] as? String ?: "live-crawling",
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.URL_PROCESSING_STARTED -> SearchEvent.UrlProcessingStarted(
            sessionId = event.sessionId,
            url = event.url ?: "",
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.URL_MARKDOWN_COMPLETE -> SearchEvent.UrlProcessed(
            sessionId = event.sessionId,
            url = event.url ?: "",
            accessType = event.metadata["accessType"] as? String ?: "UNCACHED",
            title = event.title,
            description = event.description,
            markdownLength = (event.metadata["markdownLength"] as? Number)?.toInt(),
            isPreview = false,
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.URL_HTML_PREVIEW_READY -> SearchEvent.UrlProcessed(
            sessionId = event.sessionId,
            url = event.url ?: "",
            accessType = event.metadata["accessType"] as? String ?: "UNCACHED",
            title = event.title,
            description = event.description,
            markdownLength = (event.metadata["markdownLength"] as? Number)?.toInt(),
            isPreview = true,
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.URL_PROCESSING_FAILED -> SearchEvent.UrlProcessed(
            sessionId = event.sessionId,
            url = event.url ?: "",
            accessType = "FAILED",
            errorMessage = event.metadata["errorMessage"] as? String,
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.SOURCES_EVALUATED -> SearchEvent.SourcesEvaluated(
            sessionId = event.sessionId,
            processedUrlCount = (event.metadata["processedUrlCount"] as? Number)?.toInt() ?: 0,
            relevantCount = (event.metadata["relevantCount"] as? Number)?.toInt() ?: 0,
            isGoodEnough = event.metadata["isGoodEnough"] as? Boolean ?: false,
            reason = event.metadata["reason"] as? String,
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.ANSWER_CHUNK -> SearchEvent.AnswerChunk(
            sessionId = event.sessionId,
            chunk = event.metadata["chunk"] as? String ?: "",
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.FOLLOW_UP_QUERY_GENERATED -> SearchEvent.FollowUpSearchStarted(
            sessionId = event.sessionId,
            followUpQueries = (event.metadata["followUpQueries"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            whatsMissing = event.metadata["whatsMissing"] as? String,
            iterationNumber = (event.metadata["iterationNumber"] as? Number)?.toInt() ?: 0,
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.SYNTHESIS_COMPLETE -> SearchEvent.SynthesisIteration(
            sessionId = event.sessionId,
            iterationNumber = (event.metadata["iterationNumber"] as? Number)?.toInt() ?: 0,
            status = event.metadata["status"] as? String ?: "UNKNOWN",
            sourceCount = (event.metadata["sourceCount"] as? Number)?.toInt() ?: 0,
            followUpQueries = (event.metadata["followUpQueries"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            timestampMs = event.timestampMs
        )

        SearchFlowEventType.SESSION_COMPLETED -> {
            // SessionCompleted requires sessionDetail which is complex - handle specially in orchestrator
            // Return null here as this needs special handling with actual session data
            null
        }

        SearchFlowEventType.SESSION_ERROR, SearchFlowEventType.SESSION_TIMEOUT -> SearchEvent.SessionError(
            sessionId = event.sessionId,
            errorType = event.metadata["errorType"] as? String ?: event.eventType.name,
            errorMessage = event.metadata["errorMessage"] as? String ?: "Unknown error",
            timestampMs = event.timestampMs
        )

        // Events without SSE equivalent (timeline-only for debugging)
        SearchFlowEventType.DISCOVERY_STARTED,
        SearchFlowEventType.DISCOVERY_SERP_COMPLETE,
        SearchFlowEventType.DISCOVERY_HYBRID_COMPLETE,
        SearchFlowEventType.DISCOVERY_KG_COMPLETE,
        SearchFlowEventType.DISCOVERY_FILE_SEARCH_COMPLETE,
        SearchFlowEventType.QUERY_PROCESSING_STARTED,
        SearchFlowEventType.QUERY_PROCESSING_COMPLETE,
        SearchFlowEventType.URL_LINK_DISCOVERY_COMPLETE,
        SearchFlowEventType.SYNTHESIS_STARTED -> null
    }

    override fun isStreamingSearchEndpointEvent(eventType: SearchFlowEventType): Boolean = when (eventType) {
        SearchFlowEventType.SESSION_STARTED,
        SearchFlowEventType.URL_PROCESSING_STARTED,
        SearchFlowEventType.URL_HTML_PREVIEW_READY,
        SearchFlowEventType.URL_MARKDOWN_COMPLETE,
        SearchFlowEventType.URL_PROCESSING_FAILED,
        SearchFlowEventType.SOURCES_EVALUATED,
        SearchFlowEventType.ANSWER_CHUNK,
        SearchFlowEventType.FOLLOW_UP_QUERY_GENERATED,
        SearchFlowEventType.SYNTHESIS_COMPLETE,
        SearchFlowEventType.SESSION_ERROR,
        SearchFlowEventType.SESSION_TIMEOUT -> true

        // SESSION_COMPLETED needs special handling
        SearchFlowEventType.SESSION_COMPLETED -> false

        // Timeline-only events
        SearchFlowEventType.DISCOVERY_STARTED,
        SearchFlowEventType.DISCOVERY_SERP_COMPLETE,
        SearchFlowEventType.DISCOVERY_HYBRID_COMPLETE,
        SearchFlowEventType.DISCOVERY_KG_COMPLETE,
        SearchFlowEventType.DISCOVERY_FILE_SEARCH_COMPLETE,
        SearchFlowEventType.QUERY_PROCESSING_STARTED,
        SearchFlowEventType.QUERY_PROCESSING_COMPLETE,
        SearchFlowEventType.URL_LINK_DISCOVERY_COMPLETE,
        SearchFlowEventType.SYNTHESIS_STARTED -> false
    }
}
