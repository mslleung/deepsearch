package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.VisionDetectionCache
import io.deepsearch.domain.repositories.IVisionDetectionCacheRepository
import io.deepsearch.infrastructure.database.VisionDetectionCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class ExposedVisionDetectionCacheRepository(
    private val visionCacheTable: VisionDetectionCacheTable,
    private val transactionService: ITransactionService
) : IVisionDetectionCacheRepository {

    override suspend fun findByHash(contentHash: ByteArray): VisionDetectionCache? = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(contentHash)
            visionCacheTable.selectAll()
                .where { visionCacheTable.contentHash eq hashBase64 }
                .map { mapRowToEntity(it) }
                .singleOrNull()
        }

    override suspend fun upsert(cache: VisionDetectionCache): Unit = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(cache.contentHash)
            visionCacheTable.upsert(
                keys = arrayOf(visionCacheTable.contentHash)
            ) {
                it[contentHash] = hashBase64
                it[visionResponseJson] = cache.visionResponseJson
                it[createdAtEpochMs] = cache.createdAt.toEpochMilliseconds()
                it[updatedAtEpochMs] = cache.updatedAt.toEpochMilliseconds()
                it[version] = cache.version
            }
        }

    private fun mapRowToEntity(row: ResultRow): VisionDetectionCache {
        return VisionDetectionCache(
            contentHash = Base64.decode(row[visionCacheTable.contentHash]),
            visionResponseJson = row[visionCacheTable.visionResponseJson],
            createdAt = Instant.fromEpochMilliseconds(row[visionCacheTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[visionCacheTable.updatedAtEpochMs]),
            version = row[visionCacheTable.version]
        )
    }
}
