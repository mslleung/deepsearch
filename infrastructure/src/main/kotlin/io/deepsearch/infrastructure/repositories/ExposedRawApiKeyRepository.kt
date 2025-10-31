package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IRawApiKeyRepository
import io.deepsearch.infrastructure.database.RawApiKeyTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Exposed implementation of IRawApiKeyRepository.
 * 
 * Manages encrypted raw API keys in the database.
 * Encryption is handled automatically by the table's transform.
 */
class ExposedRawApiKeyRepository(
    private val rawApiKeyTable: RawApiKeyTable
) : IRawApiKeyRepository {

    override suspend fun save(userId: UserId, rawKey: String): Unit = suspendTransaction {
        // Encryption happens automatically via table transform
        rawApiKeyTable.insert {
            it[rawApiKeyTable.userId] = userId.value
            it[encryptedRawKey] = rawKey
        }
    }

    override suspend fun findByUserId(userId: UserId): String? = suspendTransaction {
        // Decryption happens automatically via table transform
        rawApiKeyTable.selectAll()
            .where { rawApiKeyTable.userId eq userId.value }
            .map { it[rawApiKeyTable.encryptedRawKey] }
            .singleOrNull()
    }

    override suspend fun delete(userId: UserId): Boolean = suspendTransaction {
        rawApiKeyTable.deleteWhere { rawApiKeyTable.userId eq userId.value } > 0
    }
}

