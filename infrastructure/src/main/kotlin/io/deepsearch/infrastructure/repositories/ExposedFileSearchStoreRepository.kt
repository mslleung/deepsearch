package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.FileSearchStore
import io.deepsearch.domain.repositories.IFileSearchStoreRepository
import io.deepsearch.infrastructure.database.FileSearchStoreTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed R2DBC implementation of the file search store repository.
 */
@OptIn(ExperimentalTime::class)
class ExposedFileSearchStoreRepository(
    private val fileSearchStoreTable: FileSearchStoreTable,
    private val transactionService: ITransactionService
) : IFileSearchStoreRepository {

    override suspend fun findByDomain(domain: String): FileSearchStore? = transactionService.withTransaction {
        fileSearchStoreTable.selectAll()
            .where { fileSearchStoreTable.domain eq domain }
            .map { toDomain(it) }
            .singleOrNull()
    }

    override suspend fun findByGeminiStoreName(geminiStoreName: String): FileSearchStore? = transactionService.withTransaction {
        fileSearchStoreTable.selectAll()
            .where { fileSearchStoreTable.geminiStoreName eq geminiStoreName }
            .map { toDomain(it) }
            .singleOrNull()
    }

    override suspend fun create(store: FileSearchStore): FileSearchStore = transactionService.withTransaction {
        val id = fileSearchStoreTable.insert {
            it[domain] = store.domain
            it[geminiStoreName] = store.geminiStoreName
            it[createdAtEpochMs] = store.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = store.updatedAt.toEpochMilliseconds()
            it[version] = store.version
        }[fileSearchStoreTable.id]

        store.copy(id = id)
    }

    override suspend fun update(store: FileSearchStore): FileSearchStore = transactionService.withTransaction {
        val id = store.id ?: throw IllegalArgumentException("Cannot update store without ID")
        val rows = fileSearchStoreTable.update({ fileSearchStoreTable.id eq id }) {
            it[domain] = store.domain
            it[geminiStoreName] = store.geminiStoreName
            it[updatedAtEpochMs] = store.updatedAt.toEpochMilliseconds()
            it[version] = store.version + 1
        }
        if (rows > 0) {
            store.copy(version = store.version + 1)
        } else {
            throw IllegalStateException("FileSearchStore not found or version mismatch")
        }
    }

    override suspend fun delete(store: FileSearchStore): Unit = transactionService.withTransaction {
        val id = store.id ?: throw IllegalArgumentException("Cannot delete store without ID")
        fileSearchStoreTable.deleteWhere { fileSearchStoreTable.id eq id }
    }

    override suspend fun findAll(): List<FileSearchStore> = transactionService.withTransaction {
        fileSearchStoreTable.selectAll()
            .map { toDomain(it) }
            .toList()
    }

    private fun toDomain(row: ResultRow): FileSearchStore {
        return FileSearchStore(
            id = row[fileSearchStoreTable.id],
            domain = row[fileSearchStoreTable.domain],
            geminiStoreName = row[fileSearchStoreTable.geminiStoreName],
            createdAt = Instant.fromEpochMilliseconds(row[fileSearchStoreTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[fileSearchStoreTable.updatedAtEpochMs]),
            version = row[fileSearchStoreTable.version]
        )
    }
}

