package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.HiddenContainerTableCache
import io.deepsearch.domain.repositories.IHiddenContainerTableCacheRepository
import io.deepsearch.infrastructure.database.HiddenContainerTableCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class ExposedHiddenContainerTableCacheRepository(
    private val hiddenContainerTable: HiddenContainerTableCacheTable,
    private val transactionService: ITransactionService
) : IHiddenContainerTableCacheRepository {

    override suspend fun findByHash(structuralHtmlHash: ByteArray): HiddenContainerTableCache? = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(structuralHtmlHash)
            hiddenContainerTable.selectAll()
                .where { hiddenContainerTable.structuralHtmlHash eq hashBase64 }
                .map { mapRowToEntity(it) }
                .singleOrNull()
        }

    override suspend fun findByHashes(hashes: List<ByteArray>): List<HiddenContainerTableCache> = 
        transactionService.withTransaction {
            if (hashes.isEmpty()) return@withTransaction emptyList()
            
            val hashesBase64 = hashes.map { Base64.encode(it) }
            hiddenContainerTable.selectAll()
                .where { hiddenContainerTable.structuralHtmlHash inList hashesBase64 }
                .map { mapRowToEntity(it) }
                .toList()
        }

    override suspend fun upsert(cache: HiddenContainerTableCache): Unit = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(cache.structuralHtmlHash)
            hiddenContainerTable.upsert(
                keys = arrayOf(hiddenContainerTable.structuralHtmlHash)
            ) {
                it[structuralHtmlHash] = hashBase64
                it[hasTables] = cache.hasTables
                it[tablesJson] = cache.tablesJson
                it[createdAtEpochMs] = cache.createdAt.toEpochMilliseconds()
                it[updatedAtEpochMs] = cache.updatedAt.toEpochMilliseconds()
                it[version] = cache.version
            }
        }

    override suspend fun batchUpsert(caches: List<HiddenContainerTableCache>): Unit = 
        transactionService.withTransaction {
            if (caches.isEmpty()) return@withTransaction

            // Sort by hash to ensure consistent lock ordering and prevent deadlocks
            val sortedCaches = caches.sortedBy { Base64.encode(it.structuralHtmlHash) }

            hiddenContainerTable.batchUpsert(
                data = sortedCaches,
                keys = arrayOf(hiddenContainerTable.structuralHtmlHash)
            ) { cache ->
                val hashBase64 = Base64.encode(cache.structuralHtmlHash)
                this[hiddenContainerTable.structuralHtmlHash] = hashBase64
                this[hiddenContainerTable.hasTables] = cache.hasTables
                this[hiddenContainerTable.tablesJson] = cache.tablesJson
                this[hiddenContainerTable.createdAtEpochMs] = cache.createdAt.toEpochMilliseconds()
                this[hiddenContainerTable.updatedAtEpochMs] = cache.updatedAt.toEpochMilliseconds()
                this[hiddenContainerTable.version] = cache.version
            }
        }

    private fun mapRowToEntity(row: ResultRow): HiddenContainerTableCache {
        return HiddenContainerTableCache(
            structuralHtmlHash = Base64.decode(row[hiddenContainerTable.structuralHtmlHash]),
            hasTables = row[hiddenContainerTable.hasTables],
            tablesJson = row[hiddenContainerTable.tablesJson],
            createdAt = Instant.fromEpochMilliseconds(row[hiddenContainerTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[hiddenContainerTable.updatedAtEpochMs]),
            version = row[hiddenContainerTable.version]
        )
    }
}
