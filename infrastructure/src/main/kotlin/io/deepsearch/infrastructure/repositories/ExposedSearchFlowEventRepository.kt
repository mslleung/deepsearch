package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.entities.SearchFlowEventType
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.repositories.ISearchFlowEventRepository
import io.deepsearch.infrastructure.database.SearchFlowEventsTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed ORM implementation of ISearchFlowEventRepository.
 * Stores search flow timeline events for debugging and visualization.
 */
@OptIn(ExperimentalTime::class)
class ExposedSearchFlowEventRepository(
    private val searchFlowEventsTable: SearchFlowEventsTable,
    private val transactionService: ITransactionService
) : ISearchFlowEventRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(event: SearchFlowEvent): SearchFlowEvent = transactionService.withTransaction {
        val generatedId = searchFlowEventsTable.insert {
            it[sessionId] = event.sessionId.value
            it[eventType] = event.eventType.name
            it[timestampMs] = event.timestampMs
            it[durationMs] = extractDurationMs(event)
            it[url] = extractUrl(event)
            it[query] = extractQuery(event)
            it[title] = extractTitle(event)
            it[description] = extractDescription(event)
            it[metadata] = serializeMetadata(event)
            it[createdAtEpochMs] = event.createdAt.toEpochMilliseconds()
        }[searchFlowEventsTable.id]

        event.withId(generatedId)
    }

    override suspend fun saveAll(events: List<SearchFlowEvent>): List<SearchFlowEvent> = transactionService.withTransaction {
        events.map { event ->
            val generatedId = searchFlowEventsTable.insert {
                it[sessionId] = event.sessionId.value
                it[eventType] = event.eventType.name
                it[timestampMs] = event.timestampMs
                it[durationMs] = extractDurationMs(event)
                it[url] = extractUrl(event)
                it[query] = extractQuery(event)
                it[title] = extractTitle(event)
                it[description] = extractDescription(event)
                it[metadata] = serializeMetadata(event)
                it[createdAtEpochMs] = event.createdAt.toEpochMilliseconds()
            }[searchFlowEventsTable.id]
            
            event.withId(generatedId)
        }
    }

    override suspend fun findBySessionId(sessionId: QuerySessionId): List<SearchFlowEvent> = transactionService.withTransaction {
        searchFlowEventsTable.selectAll()
            .where { searchFlowEventsTable.sessionId eq sessionId.value }
            .orderBy(searchFlowEventsTable.timestampMs, SortOrder.ASC)
            .map { mapRowToSearchFlowEvent(it) }
            .toList()
    }

    override suspend fun findBySessionIdAndType(
        sessionId: QuerySessionId,
        eventType: SearchFlowEventType
    ): List<SearchFlowEvent> = transactionService.withTransaction {
        searchFlowEventsTable.selectAll()
            .where { 
                (searchFlowEventsTable.sessionId eq sessionId.value) and 
                (searchFlowEventsTable.eventType eq eventType.name)
            }
            .orderBy(searchFlowEventsTable.timestampMs, SortOrder.ASC)
            .map { mapRowToSearchFlowEvent(it) }
            .toList()
    }

    override suspend fun countBySessionId(sessionId: QuerySessionId): Long = transactionService.withTransaction {
        searchFlowEventsTable.selectAll()
            .where { searchFlowEventsTable.sessionId eq sessionId.value }
            .count()
    }

    override suspend fun deleteBySessionId(sessionId: QuerySessionId): Long = transactionService.withTransaction {
        searchFlowEventsTable.deleteWhere { 
            searchFlowEventsTable.sessionId eq sessionId.value 
        }.toLong()
    }

    // ============ Field Extraction (for indexed columns) ============

    private fun extractUrl(event: SearchFlowEvent): String? = when (event) {
        is SearchFlowEvent.SessionStarted -> event.url
        is SearchFlowEvent.UrlProcessingStarted -> event.url
        is SearchFlowEvent.UrlHtmlPreviewReady -> event.url
        is SearchFlowEvent.UrlLinkDiscoveryComplete -> event.url
        is SearchFlowEvent.UrlMarkdownComplete -> event.url
        is SearchFlowEvent.UrlProcessingFailed -> event.url
        is SearchFlowEvent.SessionError -> event.affectedUrl
        else -> null
    }

    private fun extractQuery(event: SearchFlowEvent): String? = when (event) {
        is SearchFlowEvent.SessionStarted -> event.query
        is SearchFlowEvent.DiscoverySerpComplete -> event.query
        else -> null
    }

    private fun extractTitle(event: SearchFlowEvent): String? = when (event) {
        is SearchFlowEvent.UrlHtmlPreviewReady -> event.title
        is SearchFlowEvent.UrlMarkdownComplete -> event.title
        else -> null
    }

    private fun extractDescription(event: SearchFlowEvent): String? = when (event) {
        is SearchFlowEvent.UrlHtmlPreviewReady -> event.description
        is SearchFlowEvent.UrlMarkdownComplete -> event.description
        else -> null
    }

    private fun extractDurationMs(event: SearchFlowEvent): Long? = when (event) {
        is SearchFlowEvent.DiscoverySerpComplete -> event.durationMs
        else -> null
    }

    // ============ Serialization ============

    private fun serializeMetadata(event: SearchFlowEvent): String {
        val jsonObject = buildJsonObject {
            when (event) {
                is SearchFlowEvent.SessionStarted -> {
                    put("mode", JsonPrimitive(event.mode))
                }
                is SearchFlowEvent.SessionError -> {
                    put("errorType", JsonPrimitive(event.errorType))
                    put("errorMessage", JsonPrimitive(event.errorMessage))
                    event.errorCategory?.let { put("errorCategory", JsonPrimitive(it)) }
                    event.affectedUrl?.let { put("affectedUrl", JsonPrimitive(it)) }
                    event.technicalDetails?.let { put("technicalDetails", JsonPrimitive(it)) }
                }
                is SearchFlowEvent.DiscoverySerpComplete -> {
                    put("linksFound", JsonPrimitive(event.linksFound))
                }
                is SearchFlowEvent.UrlHtmlPreviewReady -> {
                    put("accessType", JsonPrimitive(event.accessType))
                    event.markdownLength?.let { put("markdownLength", JsonPrimitive(it)) }
                }
                is SearchFlowEvent.UrlMarkdownComplete -> {
                    put("markdownLength", JsonPrimitive(event.markdownLength))
                    put("accessType", JsonPrimitive(event.accessType))
                    put("wasCached", JsonPrimitive(event.wasCached))
                }
                is SearchFlowEvent.UrlProcessingFailed -> {
                    put("errorMessage", JsonPrimitive(event.errorMessage))
                }
                is SearchFlowEvent.SourcesEvaluated -> {
                    put("processedUrlCount", JsonPrimitive(event.processedUrlCount))
                    put("relevantCount", JsonPrimitive(event.relevantCount))
                    put("isGoodEnough", JsonPrimitive(event.isGoodEnough))
                    event.reason?.let { put("reason", JsonPrimitive(it)) }
                }
                is SearchFlowEvent.SynthesisComplete -> {
                    put("iterationNumber", JsonPrimitive(event.iterationNumber))
                    put("sourceCount", JsonPrimitive(event.sourceCount))
                    put("status", JsonPrimitive(event.status))
                    put("followUpQueries", kotlinx.serialization.json.JsonArray(event.followUpQueries.map { JsonPrimitive(it) }))
                }
                is SearchFlowEvent.AnswerChunk -> {
                    put("chunk", JsonPrimitive(event.chunk))
                }
                is SearchFlowEvent.FollowUpQueryGenerated -> {
                    put("followUpQueries", kotlinx.serialization.json.JsonArray(event.followUpQueries.map { JsonPrimitive(it) }))
                    event.whatsMissing?.let { put("whatsMissing", JsonPrimitive(it)) }
                    put("iterationNumber", JsonPrimitive(event.iterationNumber))
                }
                // Events with no additional metadata
                is SearchFlowEvent.SessionCompleted,
                is SearchFlowEvent.SessionTimeout,
                is SearchFlowEvent.QueryProcessingStarted,
                is SearchFlowEvent.QueryProcessingComplete,
                is SearchFlowEvent.DiscoveryStarted,
                is SearchFlowEvent.DiscoveryHybridComplete,
                is SearchFlowEvent.DiscoveryKgComplete,
                is SearchFlowEvent.DiscoveryFileSearchComplete,
                is SearchFlowEvent.UrlProcessingStarted,
                is SearchFlowEvent.UrlLinkDiscoveryComplete,
                is SearchFlowEvent.SynthesisStarted -> { /* No additional metadata */ }
            }
        }
        return json.encodeToString(JsonElement.serializer(), jsonObject)
    }

    // ============ Deserialization ============

    private fun mapRowToSearchFlowEvent(row: ResultRow): SearchFlowEvent {
        val id = row[searchFlowEventsTable.id]
        val sessionId = QuerySessionId(row[searchFlowEventsTable.sessionId])
        val eventType = SearchFlowEventType.valueOf(row[searchFlowEventsTable.eventType])
        val timestampMs = row[searchFlowEventsTable.timestampMs]
        val durationMs = row[searchFlowEventsTable.durationMs]
        val url = row[searchFlowEventsTable.url]
        val query = row[searchFlowEventsTable.query]
        val title = row[searchFlowEventsTable.title]
        val description = row[searchFlowEventsTable.description]
        val metadataJson = row[searchFlowEventsTable.metadata]
        val createdAt = Instant.fromEpochMilliseconds(row[searchFlowEventsTable.createdAtEpochMs])

        val metadata = parseMetadataJson(metadataJson)

        return when (eventType) {
            SearchFlowEventType.SESSION_STARTED -> SearchFlowEvent.SessionStarted(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                query = query ?: "",
                url = url ?: "",
                mode = metadata.getString("mode") ?: "live-crawling"
            )
            SearchFlowEventType.SESSION_COMPLETED -> SearchFlowEvent.SessionCompleted(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.SESSION_TIMEOUT -> SearchFlowEvent.SessionTimeout(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.SESSION_ERROR -> SearchFlowEvent.SessionError(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                errorType = metadata.getString("errorType") ?: "UNKNOWN",
                errorMessage = metadata.getString("errorMessage") ?: "Unknown error",
                errorCategory = metadata.getString("errorCategory"),
                affectedUrl = metadata.getString("affectedUrl") ?: url,
                technicalDetails = metadata.getString("technicalDetails")
            )
            SearchFlowEventType.QUERY_PROCESSING_STARTED -> SearchFlowEvent.QueryProcessingStarted(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.QUERY_PROCESSING_COMPLETE -> SearchFlowEvent.QueryProcessingComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.DISCOVERY_STARTED -> SearchFlowEvent.DiscoveryStarted(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.DISCOVERY_SERP_COMPLETE -> SearchFlowEvent.DiscoverySerpComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                query = query ?: "",
                linksFound = metadata.getInt("linksFound") ?: 0,
                durationMs = durationMs ?: 0
            )
            SearchFlowEventType.DISCOVERY_HYBRID_COMPLETE -> SearchFlowEvent.DiscoveryHybridComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.DISCOVERY_KG_COMPLETE -> SearchFlowEvent.DiscoveryKgComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.DISCOVERY_FILE_SEARCH_COMPLETE -> SearchFlowEvent.DiscoveryFileSearchComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.URL_PROCESSING_STARTED -> SearchFlowEvent.UrlProcessingStarted(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                url = url ?: ""
            )
            SearchFlowEventType.URL_HTML_PREVIEW_READY -> SearchFlowEvent.UrlHtmlPreviewReady(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                url = url ?: "",
                title = title,
                description = description,
                accessType = metadata.getString("accessType") ?: "UNCACHED",
                markdownLength = metadata.getInt("markdownLength")
            )
            SearchFlowEventType.URL_LINK_DISCOVERY_COMPLETE -> SearchFlowEvent.UrlLinkDiscoveryComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                url = url ?: ""
            )
            SearchFlowEventType.URL_MARKDOWN_COMPLETE -> SearchFlowEvent.UrlMarkdownComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                url = url ?: "",
                title = title,
                description = description,
                markdownLength = metadata.getInt("markdownLength") ?: 0,
                accessType = metadata.getString("accessType") ?: "UNCACHED",
                wasCached = metadata.getBoolean("wasCached") ?: false
            )
            SearchFlowEventType.URL_PROCESSING_FAILED -> SearchFlowEvent.UrlProcessingFailed(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                url = url ?: "",
                errorMessage = metadata.getString("errorMessage") ?: "Unknown error"
            )
            SearchFlowEventType.SOURCES_EVALUATED -> SearchFlowEvent.SourcesEvaluated(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                processedUrlCount = metadata.getInt("processedUrlCount") ?: 0,
                relevantCount = metadata.getInt("relevantCount") ?: 0,
                isGoodEnough = metadata.getBoolean("isGoodEnough") ?: false,
                reason = metadata.getString("reason")
            )
            SearchFlowEventType.SYNTHESIS_STARTED -> SearchFlowEvent.SynthesisStarted(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt
            )
            SearchFlowEventType.SYNTHESIS_COMPLETE -> SearchFlowEvent.SynthesisComplete(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                iterationNumber = metadata.getInt("iterationNumber") ?: 0,
                sourceCount = metadata.getInt("sourceCount") ?: 0,
                status = metadata.getString("status") ?: "UNKNOWN",
                followUpQueries = metadata.getStringList("followUpQueries")
            )
            SearchFlowEventType.ANSWER_CHUNK -> SearchFlowEvent.AnswerChunk(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                chunk = metadata.getString("chunk") ?: ""
            )
            SearchFlowEventType.FOLLOW_UP_QUERY_GENERATED -> SearchFlowEvent.FollowUpQueryGenerated(
                id = id,
                sessionId = sessionId,
                timestampMs = timestampMs,
                createdAt = createdAt,
                followUpQueries = metadata.getStringList("followUpQueries"),
                whatsMissing = metadata.getString("whatsMissing"),
                iterationNumber = metadata.getInt("iterationNumber") ?: 0
            )
        }
    }

    // ============ JSON Parsing Helpers ============

    private fun parseMetadataJson(jsonString: String): MetadataAccessor {
        if (jsonString.isBlank() || jsonString == "{}") return MetadataAccessor(null)
        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            MetadataAccessor(if (jsonElement is kotlinx.serialization.json.JsonObject) jsonElement else null)
        } catch (e: Exception) {
            MetadataAccessor(null)
        }
    }

    private class MetadataAccessor(private val jsonObject: kotlinx.serialization.json.JsonObject?) {
        fun getString(key: String): String? {
            return try {
                jsonObject?.get(key)?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            }
        }

        fun getInt(key: String): Int? {
            return try {
                jsonObject?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
            } catch (e: Exception) {
                null
            }
        }

        fun getBoolean(key: String): Boolean? {
            return try {
                val content = jsonObject?.get(key)?.jsonPrimitive?.content
                content?.toBooleanStrictOrNull()
            } catch (e: Exception) {
                null
            }
        }

        fun getStringList(key: String): List<String> {
            return try {
                jsonObject?.get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
