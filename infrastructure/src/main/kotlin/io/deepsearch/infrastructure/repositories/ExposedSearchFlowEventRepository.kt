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
            it[durationMs] = event.durationMs
            it[url] = event.url
            it[query] = event.query
            it[title] = event.title
            it[description] = event.description
            it[metadata] = serializeMetadata(event.metadata)
            it[createdAtEpochMs] = event.createdAt.toEpochMilliseconds()
        }[searchFlowEventsTable.id]

        event.copy(id = generatedId)
    }

    override suspend fun saveAll(events: List<SearchFlowEvent>): List<SearchFlowEvent> = transactionService.withTransaction {
        events.map { event ->
            val generatedId = searchFlowEventsTable.insert {
                it[sessionId] = event.sessionId.value
                it[eventType] = event.eventType.name
                it[timestampMs] = event.timestampMs
                it[durationMs] = event.durationMs
                it[url] = event.url
                it[query] = event.query
                it[title] = event.title
                it[description] = event.description
                it[metadata] = serializeMetadata(event.metadata)
                it[createdAtEpochMs] = event.createdAt.toEpochMilliseconds()
            }[searchFlowEventsTable.id]
            
            event.copy(id = generatedId)
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
            .toLong()
    }

    override suspend fun deleteBySessionId(sessionId: QuerySessionId): Long = transactionService.withTransaction {
        searchFlowEventsTable.deleteWhere { 
            searchFlowEventsTable.sessionId eq sessionId.value 
        }.toLong()
    }

    private fun mapRowToSearchFlowEvent(row: ResultRow): SearchFlowEvent {
        return SearchFlowEvent(
            id = row[searchFlowEventsTable.id],
            sessionId = QuerySessionId(row[searchFlowEventsTable.sessionId]),
            eventType = SearchFlowEventType.valueOf(row[searchFlowEventsTable.eventType]),
            timestampMs = row[searchFlowEventsTable.timestampMs],
            durationMs = row[searchFlowEventsTable.durationMs],
            url = row[searchFlowEventsTable.url],
            query = row[searchFlowEventsTable.query],
            title = row[searchFlowEventsTable.title],
            description = row[searchFlowEventsTable.description],
            metadata = deserializeMetadata(row[searchFlowEventsTable.metadata]),
            createdAt = Instant.fromEpochMilliseconds(row[searchFlowEventsTable.createdAtEpochMs])
        )
    }

    private fun serializeMetadata(metadata: Map<String, Any>): String {
        if (metadata.isEmpty()) return "{}"
        
        val jsonObject = buildJsonObject {
            metadata.forEach { (key, value) ->
                put(key, anyToJsonElement(value))
            }
        }
        return json.encodeToString(JsonElement.serializer(), jsonObject)
    }

    private fun anyToJsonElement(value: Any): JsonElement {
        return when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is List<*> -> kotlinx.serialization.json.JsonArray(value.filterNotNull().map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeMetadata(jsonString: String): Map<String, Any> {
        if (jsonString.isBlank() || jsonString == "{}") return emptyMap()
        
        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            if (jsonElement is kotlinx.serialization.json.JsonObject) {
                jsonElement.mapValues { (_, element) -> jsonElementToAny(element) }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun jsonElementToAny(element: JsonElement): Any {
        return when (element) {
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                element.content.contains(".") -> element.content.toDoubleOrNull() ?: element.content
                else -> element.content.toLongOrNull() ?: element.content.toIntOrNull() ?: element.content
            }
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToAny(it) }
            is kotlinx.serialization.json.JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
            else -> element.toString()
        }
    }
}
