package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.ExternalApiUsage
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.ApiUsageStats
import io.deepsearch.domain.repositories.ExternalApiUsageSummary
import io.deepsearch.domain.repositories.IExternalApiUsageRepository
import io.deepsearch.infrastructure.database.ExternalApiUsageTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * Exposed ORM implementation of IExternalApiUsageRepository.
 * Stores external API usage records for cost tracking.
 */
@OptIn(ExperimentalTime::class)
class ExposedExternalApiUsageRepository(
    private val externalApiUsageTable: ExternalApiUsageTable,
    private val transactionService: ITransactionService
) : IExternalApiUsageRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(usage: ExternalApiUsage): ExternalApiUsage = transactionService.withTransaction {
        externalApiUsageTable.insert {
            it[id] = usage.id
            it[sessionId] = usage.sessionId.toStorageString()
            it[apiName] = usage.apiName
            it[endpoint] = usage.endpoint
            it[callCount] = usage.callCount
            it[costUsd] = usage.costUsd
            it[query] = usage.query
            it[metadata] = serializeMetadata(usage.metadata)
            it[createdAtEpochMs] = usage.createdAt.toEpochMilliseconds()
        }
        usage
    }

    override suspend fun findBySessionId(sessionId: SessionId): List<ExternalApiUsage> = transactionService.withTransaction {
        externalApiUsageTable.selectAll()
            .where { externalApiUsageTable.sessionId eq sessionId.toStorageString() }
            .orderBy(externalApiUsageTable.createdAtEpochMs, SortOrder.ASC)
            .map { mapRowToExternalApiUsage(it) }
            .toList()
    }

    override suspend fun findBySessionIdAndApiName(
        sessionId: SessionId,
        apiName: String
    ): List<ExternalApiUsage> = transactionService.withTransaction {
        externalApiUsageTable.selectAll()
            .where { 
                (externalApiUsageTable.sessionId eq sessionId.toStorageString()) and
                (externalApiUsageTable.apiName eq apiName)
            }
            .orderBy(externalApiUsageTable.createdAtEpochMs, SortOrder.ASC)
            .map { mapRowToExternalApiUsage(it) }
            .toList()
    }

    override suspend fun getTotalCostBySessionId(sessionId: SessionId): Double = transactionService.withTransaction {
        externalApiUsageTable.selectAll()
            .where { externalApiUsageTable.sessionId eq sessionId.toStorageString() }
            .map { it[externalApiUsageTable.costUsd] }
            .toList()
            .sum()
    }

    override suspend fun getSummaryBySessionId(sessionId: SessionId): ExternalApiUsageSummary = transactionService.withTransaction {
        val records = externalApiUsageTable.selectAll()
            .where { externalApiUsageTable.sessionId eq sessionId.toStorageString() }
            .map { it }
            .toList()

        val byApi = records
            .groupBy { it[externalApiUsageTable.apiName] }
            .mapValues { (apiName, rows) ->
                ApiUsageStats(
                    apiName = apiName,
                    callCount = rows.sumOf { it[externalApiUsageTable.callCount] },
                    totalCostUsd = rows.sumOf { it[externalApiUsageTable.costUsd] }
                )
            }

        ExternalApiUsageSummary(
            totalCalls = records.sumOf { it[externalApiUsageTable.callCount] },
            totalCostUsd = records.sumOf { it[externalApiUsageTable.costUsd] },
            byApi = byApi
        )
    }

    override suspend fun countBySessionId(sessionId: SessionId): Long = transactionService.withTransaction {
        externalApiUsageTable.selectAll()
            .where { externalApiUsageTable.sessionId eq sessionId.toStorageString() }
            .count()
            .toLong()
    }

    override suspend fun deleteBySessionId(sessionId: SessionId): Long = transactionService.withTransaction {
        externalApiUsageTable.deleteWhere { 
            externalApiUsageTable.sessionId eq sessionId.toStorageString() 
        }.toLong()
    }

    private fun mapRowToExternalApiUsage(row: ResultRow): ExternalApiUsage {
        return ExternalApiUsage(
            id = row[externalApiUsageTable.id],
            sessionId = SessionId.fromStorageString(row[externalApiUsageTable.sessionId]),
            apiName = row[externalApiUsageTable.apiName],
            endpoint = row[externalApiUsageTable.endpoint],
            callCount = row[externalApiUsageTable.callCount],
            costUsd = row[externalApiUsageTable.costUsd],
            query = row[externalApiUsageTable.query],
            metadata = deserializeMetadata(row[externalApiUsageTable.metadata]),
            createdAt = Instant.fromEpochMilliseconds(row[externalApiUsageTable.createdAtEpochMs])
        )
    }

    private fun serializeMetadata(metadata: Map<String, String>): String {
        if (metadata.isEmpty()) return "{}"
        val jsonObject = buildJsonObject {
            metadata.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    private fun deserializeMetadata(jsonString: String): Map<String, String> {
        if (jsonString.isBlank() || jsonString == "{}") return emptyMap()
        return try {
            val jsonObject = json.parseToJsonElement(jsonString) as? JsonObject ?: return emptyMap()
            jsonObject.mapValues { (_, element) -> element.jsonPrimitive.contentOrNull ?: "" }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
