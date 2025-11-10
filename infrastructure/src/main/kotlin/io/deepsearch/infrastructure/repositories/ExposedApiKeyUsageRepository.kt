package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.ApiKeyUsage
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyUsageRepository
import io.deepsearch.infrastructure.database.ApiKeyTable
import io.deepsearch.infrastructure.database.ApiKeyUsageTable
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedApiKeyUsageRepository(
    private val apiKeyUsageTable: ApiKeyUsageTable,
    private val apiKeyTable: ApiKeyTable,
    private val transactionService: TransactionService
) : IApiKeyUsageRepository {

    override suspend fun recordUsage(usage: ApiKeyUsage): ApiKeyUsage = transactionService.withTransaction {
        val id = apiKeyUsageTable.insert {
            it[apiKeyId] = usage.apiKeyId.value
            it[requestedAtEpochMs] = usage.requestedAt.toEpochMilliseconds()
            it[version] = usage.version
        }[apiKeyUsageTable.id]

        usage.id = id
        usage
    }

    override suspend fun countRequestsSince(apiKeyId: ApiKeyId, since: Instant): Long = transactionService.withTransaction {
        val results = apiKeyUsageTable.selectAll()
            .where { 
                (apiKeyUsageTable.apiKeyId eq apiKeyId.value) and
                (apiKeyUsageTable.requestedAtEpochMs greaterEq since.toEpochMilliseconds())
            }
            .map { it }
            .toList()
        results.size.toLong()
    }

    override suspend fun deleteRequestsBefore(before: Instant): Int = transactionService.withTransaction {
        apiKeyUsageTable.deleteWhere { 
            apiKeyUsageTable.requestedAtEpochMs less before.toEpochMilliseconds() 
        }
    }

    override suspend fun getUsageByDateRange(start: Instant, end: Instant): List<ApiKeyUsage> = transactionService.withTransaction {
        apiKeyUsageTable.selectAll()
            .where {
                (apiKeyUsageTable.requestedAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (apiKeyUsageTable.requestedAtEpochMs less end.toEpochMilliseconds())
            }
            .map { row ->
                ApiKeyUsage(
                    id = row[apiKeyUsageTable.id],
                    apiKeyId = ApiKeyId(row[apiKeyUsageTable.apiKeyId]),
                    requestedAt = Instant.fromEpochMilliseconds(row[apiKeyUsageTable.requestedAtEpochMs]),
                    version = row[apiKeyUsageTable.version]
                )
            }
            .toList()
    }

    override suspend fun getUsageByUserIdAndDateRange(userId: UserId, start: Instant, end: Instant): List<ApiKeyUsage> = transactionService.withTransaction {
        apiKeyUsageTable
            .innerJoin(apiKeyTable)
            .selectAll()
            .where {
                (apiKeyTable.userId eq userId.value) and
                (apiKeyUsageTable.requestedAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (apiKeyUsageTable.requestedAtEpochMs less end.toEpochMilliseconds())
            }
            .map { row ->
                ApiKeyUsage(
                    id = row[apiKeyUsageTable.id],
                    apiKeyId = ApiKeyId(row[apiKeyUsageTable.apiKeyId]),
                    requestedAt = Instant.fromEpochMilliseconds(row[apiKeyUsageTable.requestedAtEpochMs]),
                    version = row[apiKeyUsageTable.version]
                )
            }
            .toList()
    }

    override suspend fun getUsageByApiKeyIdAndDateRange(apiKeyId: ApiKeyId, start: Instant, end: Instant): List<ApiKeyUsage> = transactionService.withTransaction {
        apiKeyUsageTable.selectAll()
            .where {
                (apiKeyUsageTable.apiKeyId eq apiKeyId.value) and
                (apiKeyUsageTable.requestedAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (apiKeyUsageTable.requestedAtEpochMs less end.toEpochMilliseconds())
            }
            .map { row ->
                ApiKeyUsage(
                    id = row[apiKeyUsageTable.id],
                    apiKeyId = ApiKeyId(row[apiKeyUsageTable.apiKeyId]),
                    requestedAt = Instant.fromEpochMilliseconds(row[apiKeyUsageTable.requestedAtEpochMs]),
                    version = row[apiKeyUsageTable.version]
                )
            }
            .toList()
    }
}

