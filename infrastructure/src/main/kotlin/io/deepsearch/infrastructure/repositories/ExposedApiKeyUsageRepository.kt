package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.ApiKeyUsage
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyUsageRepository
import io.deepsearch.infrastructure.database.ApiKeyTable
import io.deepsearch.infrastructure.database.ApiKeyUsageTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedApiKeyUsageRepository : IApiKeyUsageRepository {

    override suspend fun recordUsage(usage: ApiKeyUsage): ApiKeyUsage = suspendTransaction {
        val id = ApiKeyUsageTable.insert {
            it[apiKeyId] = usage.apiKeyId.value
            it[requestedAtEpochMs] = usage.requestedAt.toEpochMilliseconds()
            it[version] = usage.version
        }[ApiKeyUsageTable.id]

        usage.id = id
        usage
    }

    override suspend fun countRequestsSince(apiKeyId: ApiKeyId, since: Instant): Long = suspendTransaction {
        val results = ApiKeyUsageTable.selectAll()
            .where { 
                (ApiKeyUsageTable.apiKeyId eq apiKeyId.value) and
                (ApiKeyUsageTable.requestedAtEpochMs greaterEq since.toEpochMilliseconds())
            }
            .map { it }
            .toList()
        results.size.toLong()
    }

    override suspend fun deleteRequestsBefore(before: Instant): Int = suspendTransaction {
        ApiKeyUsageTable.deleteWhere { 
            ApiKeyUsageTable.requestedAtEpochMs less before.toEpochMilliseconds() 
        }
    }

    override suspend fun getUsageByDateRange(start: Instant, end: Instant): List<ApiKeyUsage> = suspendTransaction {
        ApiKeyUsageTable.selectAll()
            .where {
                (ApiKeyUsageTable.requestedAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (ApiKeyUsageTable.requestedAtEpochMs less end.toEpochMilliseconds())
            }
            .map { row ->
                ApiKeyUsage(
                    id = row[ApiKeyUsageTable.id],
                    apiKeyId = ApiKeyId(row[ApiKeyUsageTable.apiKeyId]),
                    requestedAt = Instant.fromEpochMilliseconds(row[ApiKeyUsageTable.requestedAtEpochMs]),
                    version = row[ApiKeyUsageTable.version]
                )
            }
            .toList()
    }

    override suspend fun getUsageByUserIdAndDateRange(userId: UserId, start: Instant, end: Instant): List<ApiKeyUsage> = suspendTransaction {
        ApiKeyUsageTable
            .innerJoin(ApiKeyTable)
            .selectAll()
            .where {
                (ApiKeyTable.userId eq userId.value) and
                (ApiKeyUsageTable.requestedAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (ApiKeyUsageTable.requestedAtEpochMs less end.toEpochMilliseconds())
            }
            .map { row ->
                ApiKeyUsage(
                    id = row[ApiKeyUsageTable.id],
                    apiKeyId = ApiKeyId(row[ApiKeyUsageTable.apiKeyId]),
                    requestedAt = Instant.fromEpochMilliseconds(row[ApiKeyUsageTable.requestedAtEpochMs]),
                    version = row[ApiKeyUsageTable.version]
                )
            }
            .toList()
    }

    override suspend fun getUsageByApiKeyIdAndDateRange(apiKeyId: ApiKeyId, start: Instant, end: Instant): List<ApiKeyUsage> = suspendTransaction {
        ApiKeyUsageTable.selectAll()
            .where {
                (ApiKeyUsageTable.apiKeyId eq apiKeyId.value) and
                (ApiKeyUsageTable.requestedAtEpochMs greaterEq start.toEpochMilliseconds()) and
                (ApiKeyUsageTable.requestedAtEpochMs less end.toEpochMilliseconds())
            }
            .map { row ->
                ApiKeyUsage(
                    id = row[ApiKeyUsageTable.id],
                    apiKeyId = ApiKeyId(row[ApiKeyUsageTable.apiKeyId]),
                    requestedAt = Instant.fromEpochMilliseconds(row[ApiKeyUsageTable.requestedAtEpochMs]),
                    version = row[ApiKeyUsageTable.version]
                )
            }
            .toList()
    }
}

