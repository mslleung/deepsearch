package io.deepsearch.presentation.controllers

import io.deepsearch.application.services.IApiKeyService
import io.deepsearch.application.services.ISearchService
import io.deepsearch.application.services.IUserSubscriptionService
import io.deepsearch.application.services.SearchEvent
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.presentation.dto.ImageDto
import io.deepsearch.presentation.dto.SearchEventDto
import io.deepsearch.presentation.dto.SearchRequest
import io.deepsearch.presentation.dto.toDetailDto
import io.deepsearch.presentation.dto.toDto
import kotlin.io.encoding.Base64
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.channels.ClosedChannelException

class SearchController(
    private val searchService: ISearchService,
    private val apiKeyService: IApiKeyService,
    private val subscriptionPlanService: IUserSubscriptionService,
    private val webpageImageRepository: IWebpageImageRepository
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    suspend fun searchWebsite(call: ApplicationCall) {
        // Get API key from bearer token
        val principal = call.principal<UserIdPrincipal>()
        val rawApiKey = principal?.name

        if (rawApiKey == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "API key required"))
            return
        }

        // Validate API key
        val isApikeyOk = apiKeyService.validateApiKey(rawApiKey)
        if (!isApikeyOk) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
            return
        }

        val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey)!!

        // Check usage limit (subscription quota)
        val hasUsageRemaining = subscriptionPlanService.checkUsageLimit(apiKey.userId)
        if (!hasUsageRemaining) {
            call.respond(
                HttpStatusCode.PaymentRequired,
                mapOf(
                    "error" to "Usage limit exceeded",
                    "message" to "You have reached your plan's search limit. Please upgrade your plan."
                )
            )
            return
        }

        apiKeyService.incrementApiKeyUsage(rawApiKey)

        val request = call.receive<SearchRequest>()

        // Validate cache expiry parameter
        request.maxCacheAge?.let { maxCacheAge ->
            if (maxCacheAge <= 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "maxCacheAge must be positive. Received: $maxCacheAge")
                )
                return
            }
        }
        
        // Validate language pattern
        try {
            request.validateLanguagePattern()
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to e.message)
            )
            return
        }
        
        // Validate OCR language
        try {
            request.validateOcrLanguage()
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to e.message)
            )
            return
        }

        // Parse and validate mode
        val searchMode = request.toSearchMode()

        // Execute search and get session detail
        val sessionDetail = searchService.searchWebsite(
            request.query,
            request.url,
            request.maxCacheAge,
            searchMode,
            apiKey.id!!,
            apiKey.userId,
            request.languagePattern,
            request.toOcrLanguage(),
            request.includeImages ?: false
        )

        // Consume usage after successful search
        subscriptionPlanService.consumeUsage(apiKey.userId)

        // Fetch images if there are any referenced in the answer
        val images = if (sessionDetail.imageIds.isNotEmpty()) {
            fetchImagesByIds(sessionDetail.imageIds)
        } else {
            emptyMap()
        }

        // Convert to DTO and respond
        val responseDto = sessionDetail.session.toDetailDto(
            sessionDetail.urlAccesses,
            sessionDetail.cachedWebpages,
            images
        )
        call.respond(HttpStatusCode.OK, responseDto)
    }

    /**
     * Stream search events via SSE.
     * Validates auth, starts search, and streams events until completion.
     * Auth via query param apiKey since EventSource cannot set headers.
     */
    suspend fun streamSearch(call: ApplicationCall, sse: ServerSSESession) {
        try {
            // Validate API key from query param (EventSource cannot set headers)
            val rawApiKey = call.request.queryParameters["apiKey"]
            if (rawApiKey == null) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "Unauthorized",
                                "Missing apiKey parameter",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }

            val isApikeyOk = apiKeyService.validateApiKey(rawApiKey)
            if (!isApikeyOk) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "Unauthorized",
                                "Invalid API key",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }

            val apiKey = apiKeyService.getApiKeyByRawKey(rawApiKey)!!

            // Check usage limit
            val hasUsageRemaining = subscriptionPlanService.checkUsageLimit(apiKey.userId)
            if (!hasUsageRemaining) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "UsageLimitExceeded",
                                "You have reached your plan's search limit. Please upgrade your plan.",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }

            // Parse request params
            val query = call.request.queryParameters["query"]
            val url = call.request.queryParameters["url"]
            val maxCacheAge = call.request.queryParameters["maxCacheAge"]?.toLongOrNull()
            val modeParam = call.request.queryParameters["mode"]
            val languagePattern = call.request.queryParameters["languagePattern"]
            val ocrLanguageParam = call.request.queryParameters["ocrLanguage"]
            val includeImages = call.request.queryParameters["includeImages"]?.toBoolean() ?: false

            if (query.isNullOrBlank() || url.isNullOrBlank()) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "BadRequest",
                                "Missing query or url parameter",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }

            // Validate cache expiry
            if (maxCacheAge != null && maxCacheAge <= 0) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "BadRequest",
                                "maxCacheAge must be positive",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }

            // Parse mode and validate language pattern
            val searchRequest = SearchRequest(query, url, maxCacheAge, modeParam, languagePattern, ocrLanguageParam, includeImages)
            val searchMode = try {
                searchRequest.toSearchMode()
            } catch (e: IllegalArgumentException) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "BadRequest",
                                e.message ?: "Invalid mode",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }
            
            // Validate language pattern
            try {
                searchRequest.validateLanguagePattern()
            } catch (e: IllegalArgumentException) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "BadRequest",
                                e.message ?: "Invalid language pattern",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }
            
            // Validate OCR language
            try {
                searchRequest.validateOcrLanguage()
            } catch (e: IllegalArgumentException) {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                "BadRequest",
                                e.message ?: "Invalid OCR language",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
                return
            }

            apiKeyService.incrementApiKeyUsage(rawApiKey)
            subscriptionPlanService.consumeUsage(apiKey.userId)

            // Execute streaming search and forward events
            val eventFlow = searchService.executeStreaming(
                query,
                url,
                maxCacheAge,
                searchMode,
                apiKey.id!!,
                apiKey.userId,
                languagePattern,
                searchRequest.toOcrLanguage(),
                includeImages
            )

            // Use onEach to send SSE events, then first to stop at terminal event
            // This ensures we don't wait for upstream flow cleanup after session completes
            eventFlow
                .onEach { event ->
                    // Map application-layer SearchEvent to presentation-layer SearchEventDto
                    val images = if (event is SearchEvent.SessionCompleted && event.imageIds.isNotEmpty()) {
                        fetchImagesByIds(event.imageIds)
                    } else {
                        emptyMap()
                    }
                    val eventDto = event.toDto(images)
                    val payload = Json.encodeToString(SearchEventDto.serializer(), eventDto)
                    sse.send(ServerSentEvent(payload))
                }
                .first { event ->
                    // Stop collecting after terminal event
                    event is SearchEvent.SessionCompleted || event is SearchEvent.SessionError
                }

        } catch (e: Exception) {
            // Handle channel closure gracefully - this is expected when client disconnects or stream ends
            if (e.isChannelClosed()) {
                logger.debug("SSE channel closed (client disconnected or stream ended)")
                return
            }
            
            logger.error("Error in streaming search: {}", e.message, e)
            try {
                sse.send(
                    ServerSentEvent(
                        Json.encodeToString(
                            SearchEventDto.serializer(),
                            SearchEventDto.SessionErrorDto(
                                "",
                                e::class.simpleName ?: "Unknown",
                                e.message ?: "Unknown error",
                                System.currentTimeMillis()
                            )
                        )
                    )
                )
            } catch (sendError: Exception) {
                // If we can't send the error, the channel is likely closed - this is fine
                if (sendError.isChannelClosed()) {
                    logger.debug("SSE channel closed while sending error event")
                } else {
                    logger.warn("Failed to send error event: {}", sendError.message)
                }
            }
        }
    }
    
    /**
     * Check if an exception indicates a closed channel (expected during SSE shutdown).
     */
    private fun Exception.isChannelClosed(): Boolean {
        return this is ClosedWriteChannelException ||
            this is ClosedChannelException ||
            this is CancellationException ||
            this.cause?.let { it is ClosedWriteChannelException || it is ClosedChannelException } == true
    }

    /**
     * Fetch images by their IDs (format: "img-{urlSafeBase64Hash}") and convert to ImageDto map.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun fetchImagesByIds(imageIds: List<String>): Map<String, ImageDto> {
        if (imageIds.isEmpty()) return emptyMap()

        // Convert image IDs back to byte array hashes
        val idToHash = imageIds.mapNotNull { imageId ->
            if (imageId.startsWith("img-")) {
                try {
                    // Reverse URL-safe encoding: - -> +, _ -> /
                    val base64Hash = imageId.removePrefix("img-")
                        .replace("-", "+")
                        .replace("_", "/")
                    // Add padding if needed
                    val paddedHash = when (base64Hash.length % 4) {
                        2 -> "$base64Hash=="
                        3 -> "$base64Hash="
                        else -> base64Hash
                    }
                    imageId to Base64.decode(paddedHash)
                } catch (e: Exception) {
                    logger.warn("Failed to decode image ID {}: {}", imageId, e.message)
                    null
                }
            } else null
        }.toMap()

        if (idToHash.isEmpty()) return emptyMap()

        // Fetch all images by their hashes
        val hashes = idToHash.values.toList()
        val images = webpageImageRepository.findByHashes(hashes)

        // Build result map
        val result = mutableMapOf<String, ImageDto>()
        images.forEach { image ->
            // Find the image ID for this hash
            idToHash.entries.find { it.value.contentEquals(image.imageBytesHash) }?.let { (imageId, _) ->
                result[imageId] = ImageDto(
                    base64 = Base64.encode(image.imageBytes),
                    mimeType = image.mimeType
                )
            }
        }

        logger.debug("Fetched {} images out of {} requested", result.size, imageIds.size)
        return result
    }
}
