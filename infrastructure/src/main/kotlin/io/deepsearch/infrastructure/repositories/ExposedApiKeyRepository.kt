package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.ApiKey
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import io.deepsearch.infrastructure.database.ApiKeyTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedApiKeyRepository : IApiKeyRepository {

    override suspend fun save(apiKey: ApiKey): ApiKey = suspendTransaction {
        val id = ApiKeyTable.insert {
            it[userId] = apiKey.userId.value
            it[keyHash] = apiKey.keyHash
            it[keyPrefix] = apiKey.keyPrefix
            it[name] = apiKey.name
            it[type] = apiKey.type.name
            it[rateLimitPerMinute] = apiKey.rateLimitPerMinute
            it[createdAtEpochMs] = apiKey.createdAt.toEpochMilliseconds()
            it[lastUsedAtEpochMs] = apiKey.lastUsedAt?.toEpochMilliseconds()
            it[usageCount] = apiKey.usageCount
        }[ApiKeyTable.id]

        apiKey.id = ApiKeyId(id)
        apiKey
    }

    override suspend fun findById(id: ApiKeyId): ApiKey? = suspendTransaction {
        ApiKeyTable.selectAll()
            .where { ApiKeyTable.id eq id.value }
            .map { mapRowToApiKey(it) }
            .singleOrNull()
    }

    override suspend fun findByKeyHash(hash: String): ApiKey? = suspendTransaction {
        ApiKeyTable.selectAll()
            .where { ApiKeyTable.keyHash eq hash }
            .map { mapRowToApiKey(it) }
            .singleOrNull()
    }

    override suspend fun findByUserId(userId: UserId): List<ApiKey> = suspendTransaction {
        ApiKeyTable.selectAll()
            .where { ApiKeyTable.userId eq userId.value }
            .map { mapRowToApiKey(it) }
            .toList()
    }

    override suspend fun findByUserIdAndType(userId: UserId, type: ApiKeyType): ApiKey? = suspendTransaction {
        ApiKeyTable.selectAll()
            .where { (ApiKeyTable.userId eq userId.value) and (ApiKeyTable.type eq type.name) }
            .map { mapRowToApiKey(it) }
            .singleOrNull()
    }

    override suspend fun delete(id: ApiKeyId): Boolean = suspendTransaction {
        ApiKeyTable.deleteWhere { ApiKeyTable.id eq id.value } > 0
    }

    override suspend fun update(apiKey: ApiKey): ApiKey = suspendTransaction {
        ApiKeyTable.update({ ApiKeyTable.id eq apiKey.id!!.value }) {
            it[lastUsedAtEpochMs] = apiKey.lastUsedAt?.toEpochMilliseconds()
            it[usageCount] = apiKey.usageCount
        }
        apiKey
    }

    private fun mapRowToApiKey(row: ResultRow): ApiKey {
        return ApiKey(
            id = ApiKeyId(row[ApiKeyTable.id]),
            userId = UserId(row[ApiKeyTable.userId]),
            keyHash = row[ApiKeyTable.keyHash],
            keyPrefix = row[ApiKeyTable.keyPrefix],
            name = row[ApiKeyTable.name],
            type = ApiKeyType.valueOf(row[ApiKeyTable.type]),
            rateLimitPerMinute = row[ApiKeyTable.rateLimitPerMinute],
            createdAt = Instant.fromEpochMilliseconds(row[ApiKeyTable.createdAtEpochMs]),
            lastUsedAt = row[ApiKeyTable.lastUsedAtEpochMs]?.let { Instant.fromEpochMilliseconds(it) },
            usageCount = row[ApiKeyTable.usageCount]
        )
    }
}

