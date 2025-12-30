package io.deepsearch.presentation.dto

import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.models.valueobjects.CachedUrlAccess
import io.deepsearch.domain.models.valueobjects.FailedUrlAccess
import io.deepsearch.domain.models.valueobjects.UncachedUrlAccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

/**
 * DTOs for search events sent over SSE.
 * These are the serializable presentation-layer representations of SearchEvent.
 */
@Serializable
sealed class SearchEventDto {
    abstract val sessionId: String
    abstract val timestampMs: Long

    @Serializable
    @SerialName("session_created")
    data class SessionCreatedDto(
        override val sessionId: String,
        val query: String,
        val url: String,
        val mode: String,
        override val timestampMs: Long
    ) : SearchEventDto()

    @Serializable
    @SerialName("url_processing_started")
    data class UrlProcessingStartedDto(
        override val sessionId: String,
        val url: String,
        override val timestampMs: Long
    ) : SearchEventDto()

    @Serializable
    @SerialName("url_processed")
    data class UrlProcessedDto(
        override val sessionId: String,
        val url: String,
        val accessType: String,
        val title: String? = null,
        val description: String? = null,
        val markdownLength: Int? = null,
        val errorMessage: String? = null,
        val isPreview: Boolean = false,
        override val timestampMs: Long
    ) : SearchEventDto()

    /**
     * Emitted when a URL's content is upgraded from preview (simple text) to full markdown.
     * This allows the frontend to update the source display with complete content.
     */
    @Serializable
    @SerialName("url_content_upgraded")
    data class UrlContentUpgradedDto(
        override val sessionId: String,
        val url: String,
        val title: String? = null,
        val description: String? = null,
        val markdownLength: Int,
        override val timestampMs: Long
    ) : SearchEventDto()

    @Serializable
    @SerialName("sources_evaluated")
    data class SourcesEvaluatedDto(
        override val sessionId: String,
        val processedUrlCount: Int,
        val relevantCount: Int,
        val isGoodEnough: Boolean,
        val reason: String? = null,
        override val timestampMs: Long
    ) : SearchEventDto()

    /**
     * Answer chunk event for streaming answer generation.
     * Multiple chunks are emitted as the answer is progressively generated.
     */
    @Serializable
    @SerialName("answer_chunk")
    data class AnswerChunkDto(
        override val sessionId: String,
        val chunk: String,
        override val timestampMs: Long
    ) : SearchEventDto()

    /**
     * Session completed event with full session detail for the frontend.
     * Includes contentSources, answerSources, traversedUrls, and images.
     */
    @Serializable
    @SerialName("session_completed")
    data class SessionCompletedDto(
        override val sessionId: String,
        val answer: String?,
        val finishReason: String,
        val durationMs: Long?,
        val mode: String,
        val contentSources: List<ContentSourceDto>,
        val answerSources: List<String>,
        val traversedUrls: List<UrlAccessDto>,
        val images: Map<String, ImageDto> = emptyMap(), // Map of imageId -> ImageDto
        override val timestampMs: Long
    ) : SearchEventDto()

    @Serializable
    @SerialName("session_error")
    data class SessionErrorDto(
        override val sessionId: String,
        val errorType: String,
        val errorMessage: String,
        override val timestampMs: Long
    ) : SearchEventDto()
}

/**
 * Maps a SearchEvent (application layer) to SearchEventDto (presentation layer).
 * Converts QuerySessionId value objects to String for serialization.
 * 
 * @param images Optional map of image IDs to ImageDto for SessionCompleted events.
 *               Pass this when images need to be included in the response.
 */
@OptIn(ExperimentalTime::class)
fun SearchEvent.toDto(images: Map<String, ImageDto> = emptyMap()): SearchEventDto {
    return when (this) {
        is SearchEvent.SessionCreated -> SearchEventDto.SessionCreatedDto(
            sessionId = sessionId.value,
            query = query,
            url = url,
            mode = mode,
            timestampMs = timestampMs
        )

        is SearchEvent.UrlProcessingStarted -> SearchEventDto.UrlProcessingStartedDto(
            sessionId = sessionId.value,
            url = url,
            timestampMs = timestampMs
        )

        is SearchEvent.UrlProcessed -> SearchEventDto.UrlProcessedDto(
            sessionId = sessionId.value,
            url = url,
            accessType = accessType,
            title = title,
            description = description,
            markdownLength = markdownLength,
            errorMessage = errorMessage,
            isPreview = isPreview,
            timestampMs = timestampMs
        )

        is SearchEvent.UrlContentUpgraded -> SearchEventDto.UrlContentUpgradedDto(
            sessionId = sessionId.value,
            url = url,
            title = title,
            description = description,
            markdownLength = markdownLength,
            timestampMs = timestampMs
        )

        is SearchEvent.SourcesEvaluated -> SearchEventDto.SourcesEvaluatedDto(
            sessionId = sessionId.value,
            processedUrlCount = processedUrlCount,
            relevantCount = relevantCount,
            isGoodEnough = isGoodEnough,
            reason = reason,
            timestampMs = timestampMs
        )

        is SearchEvent.AnswerChunk -> SearchEventDto.AnswerChunkDto(
            sessionId = sessionId.value,
            chunk = chunk,
            timestampMs = timestampMs
        )

        is SearchEvent.SessionCompleted -> {
            val detail = sessionDetail
            val session = detail.session
            val urlAccesses = detail.urlAccesses
            val cachedWebpages = detail.cachedWebpages

            // Build URL to webpage map for efficient lookup
            val urlToWebpage = cachedWebpages.associateBy { it.url }

            // Content sources: successfully accessed URLs with their content
            val contentSources = urlAccesses
                .filterNot { it is FailedUrlAccess }
                .mapNotNull { urlAccess ->
                    val webpage = urlToWebpage[urlAccess.url]
                    val markdownContent = webpage?.markdown
                    if (markdownContent != null) {
                        ContentSourceDto(
                            url = urlAccess.url,
                            title = webpage.title,
                            description = webpage.description,
                            markdown = markdownContent,
                            isPreview = webpage.isPreview
                        )
                    } else null
                }

            // Answer sources: URLs marked as used in answer
            val answerSources = urlAccesses
                .filter { it.isUsedInAnswer }
                .map { it.url }

            // Traversed URLs with their access type
            val traversedUrls = urlAccesses.map { urlAccess ->
                UrlAccessDto(
                    url = urlAccess.url,
                    timestamp = urlAccess.timestamp.toEpochMilliseconds(),
                    type = when (urlAccess) {
                        is CachedUrlAccess -> "CACHED"
                        is UncachedUrlAccess -> "UNCACHED"
                        is FailedUrlAccess -> "FAILED"
                    },
                    exceptionType = (urlAccess as? FailedUrlAccess)?.exceptionType,
                    exceptionMessage = (urlAccess as? FailedUrlAccess)?.message
                )
            }

            // Determine mode string
            val modeString = when (session.searchMode) {
                io.deepsearch.domain.models.valueobjects.SearchMode.LIVE_CRAWLING -> "live-crawling"
                io.deepsearch.domain.models.valueobjects.SearchMode.CACHE_ONLY -> "cache-only"
            }

            SearchEventDto.SessionCompletedDto(
                sessionId = sessionId.value,
                answer = session.answer,
                finishReason = finishReason,
                durationMs = session.durationMs,
                mode = modeString,
                contentSources = contentSources,
                answerSources = answerSources,
                traversedUrls = traversedUrls,
                images = images,
                timestampMs = timestampMs
            )
        }

        is SearchEvent.SessionError -> SearchEventDto.SessionErrorDto(
            sessionId = sessionId.value,
            errorType = errorType,
            errorMessage = errorMessage,
            timestampMs = timestampMs
        )
    }
}

