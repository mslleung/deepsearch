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
 * 
 * Uses exhaustive when-matching on the sealed class hierarchy for compile-time safety.
 */
class SearchFlowEventMapper : ISearchFlowEventMapper {

    override fun toStreamingSearchEndpointEvent(event: SearchFlowEvent): SearchEvent? = when (event) {
        is SearchFlowEvent.SessionStarted -> SearchEvent.SessionCreated(
            sessionId = event.sessionId,
            query = event.query,
            url = event.url,
            mode = event.mode,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.UrlProcessingStarted -> SearchEvent.UrlProcessingStarted(
            sessionId = event.sessionId,
            url = event.url,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.UrlMarkdownComplete -> SearchEvent.UrlProcessed(
            sessionId = event.sessionId,
            url = event.url,
            accessType = event.accessType,
            title = event.title,
            description = event.description,
            markdownLength = event.markdownLength,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.UrlProcessingFailed -> SearchEvent.UrlProcessed(
            sessionId = event.sessionId,
            url = event.url,
            accessType = "FAILED",
            errorMessage = event.errorMessage,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.SourcesEvaluated -> SearchEvent.SourcesEvaluated(
            sessionId = event.sessionId,
            processedUrlCount = event.processedUrlCount,
            relevantCount = event.relevantCount,
            isGoodEnough = event.isGoodEnough,
            reason = event.reason,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.AnswerChunk -> SearchEvent.AnswerChunk(
            sessionId = event.sessionId,
            chunk = event.chunk,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.FollowUpQueryGenerated -> SearchEvent.FollowUpSearchStarted(
            sessionId = event.sessionId,
            followUpQueries = event.followUpQueries,
            whatsMissing = event.whatsMissing,
            iterationNumber = event.iterationNumber,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.SynthesisComplete -> SearchEvent.SynthesisIteration(
            sessionId = event.sessionId,
            iterationNumber = event.iterationNumber,
            status = event.status,
            sourceCount = event.sourceCount,
            followUpQueries = event.followUpQueries,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.SessionCompleted -> {
            // SessionCompleted requires sessionDetail which is complex - handle specially in orchestrator
            // Return null here as this needs special handling with actual session data
            null
        }

        is SearchFlowEvent.SessionError -> SearchEvent.SessionError(
            sessionId = event.sessionId,
            errorType = event.errorType,
            errorMessage = event.errorMessage,
            errorCategory = event.errorCategory,
            affectedUrl = event.affectedUrl,
            technicalDetails = event.technicalDetails,
            timestampMs = event.timestampMs
        )

        is SearchFlowEvent.SessionTimeout -> SearchEvent.SessionError(
            sessionId = event.sessionId,
            errorType = "SESSION_TIMEOUT",
            errorMessage = "Session timed out",
            timestampMs = event.timestampMs
        )

        // Events without SSE equivalent (timeline-only for debugging)
        is SearchFlowEvent.DiscoveryStarted,
        is SearchFlowEvent.DiscoverySerpComplete,
        is SearchFlowEvent.DiscoveryHybridComplete,
        is SearchFlowEvent.DiscoveryKgComplete,
        is SearchFlowEvent.DiscoveryFileSearchComplete,
        is SearchFlowEvent.QueryProcessingStarted,
        is SearchFlowEvent.QueryProcessingComplete,
        is SearchFlowEvent.UrlLinksDiscovered,
        is SearchFlowEvent.SynthesisStarted -> null
    }

    override fun isStreamingSearchEndpointEvent(eventType: SearchFlowEventType): Boolean = when (eventType) {
        SearchFlowEventType.SESSION_STARTED,
        SearchFlowEventType.URL_PROCESSING_STARTED,
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
        SearchFlowEventType.URL_LINKS_DISCOVERED,
        SearchFlowEventType.SYNTHESIS_STARTED -> false
    }
}
