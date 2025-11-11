package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.ApiKey
import io.deepsearch.domain.models.valueobjects.ApiKeyId
import io.deepsearch.domain.models.valueobjects.ApiKeyType
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IApiKeyRepository
import io.deepsearch.infrastructure.database.ApiKeyTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedApiKeyRepository(
    private val apiKeyTable: ApiKeyTable,
    private val transactionService: ITransactionService,
) : IApiKeyRepository {

    override suspend fun save(apiKey: ApiKey): ApiKey = transactionService.withTransaction {
        val id = apiKeyTable.insert {
            it[userId] = apiKey.userId.value
            it[keyHash] = apiKey.keyHash
            it[keyPrefix] = apiKey.keyPrefix
            it[name] = apiKey.name
            it[type] = apiKey.type.name
            it[rateLimitPerMinute] = apiKey.rateLimitPerMinute
            it[createdAtEpochMs] = apiKey.createdAt.toEpochMilliseconds()
            it[lastUsedAtEpochMs] = apiKey.lastUsedAt?.toEpochMilliseconds()
            it[usageCount] = apiKey.usageCount
            it[version] = apiKey.version
        }[apiKeyTable.id]

        apiKey.id = ApiKeyId(id)
        apiKey
    }

    override suspend fun findById(id: ApiKeyId): ApiKey? = transactionService.withTransaction {
        apiKeyTable.selectAll()
            .where { apiKeyTable.id eq id.value }
            .map { mapRowToApiKey(it) }
            .singleOrNull()
    }

    override suspend fun findByKeyHash(hash: String): ApiKey? = transactionService.withTransaction {
        apiKeyTable.selectAll()
            .where { apiKeyTable.keyHash eq hash }
            .map { mapRowToApiKey(it) }
            .singleOrNull()
    }

    override suspend fun findByUserId(userId: UserId): List<ApiKey> = transactionService.withTransaction {
        apiKeyTable.selectAll()
            .where { apiKeyTable.userId eq userId.value }
            .map { mapRowToApiKey(it) }
            .toList()
    }

    override suspend fun findByUserIdAndType(userId: UserId, type: ApiKeyType): ApiKey? = transactionService.withTransaction {
        apiKeyTable.selectAll()
            .where { (apiKeyTable.userId eq userId.value) and (apiKeyTable.type eq type.name) }
            .map { mapRowToApiKey(it) }
            .singleOrNull()
    }

    override suspend fun countByUserIdAndType(userId: UserId, type: ApiKeyType): Long = transactionService.withTransaction {
        apiKeyTable.selectAll()
            .where { (apiKeyTable.userId eq userId.value) and (apiKeyTable.type eq type.name) }
            .count()
            .toLong()
    }

    override suspend fun delete(id: ApiKeyId): Boolean = transactionService.withTransaction {
        apiKeyTable.deleteWhere { apiKeyTable.id eq id.value } > 0
    }

    override suspend fun update(apiKey: ApiKey): ApiKey = transactionService.withTransaction {
        val affectedRows = apiKeyTable.update({ 
            (apiKeyTable.id eq apiKey.id!!.value) and (apiKeyTable.version eq apiKey.version) 
        }) {
            it[lastUsedAtEpochMs] = apiKey.lastUsedAt?.toEpochMilliseconds()
            it[usageCount] = apiKey.usageCount
            it[version] = apiKey.version + 1
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("ApiKey", apiKey.id!!.value, apiKey.version)
        }
        
        apiKey.version += 1
        apiKey
    }

    private fun mapRowToApiKey(row: ResultRow): ApiKey {
        return ApiKey(
            id = ApiKeyId(row[apiKeyTable.id]),
            userId = UserId(row[apiKeyTable.userId]),
            keyHash = row[apiKeyTable.keyHash],
            keyPrefix = row[apiKeyTable.keyPrefix],
            name = row[apiKeyTable.name],
            type = ApiKeyType.valueOf(row[apiKeyTable.type]),
            rateLimitPerMinute = row[apiKeyTable.rateLimitPerMinute],
            createdAt = Instant.fromEpochMilliseconds(row[apiKeyTable.createdAtEpochMs]),
            lastUsedAt = row[apiKeyTable.lastUsedAtEpochMs]?.let { Instant.fromEpochMilliseconds(it) },
            usageCount = row[apiKeyTable.usageCount],
            version = row[apiKeyTable.version]
        )
    }
}

