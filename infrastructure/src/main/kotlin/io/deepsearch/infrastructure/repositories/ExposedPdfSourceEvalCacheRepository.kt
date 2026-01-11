package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.PdfSourceEvalCache
import io.deepsearch.domain.repositories.IPdfSourceEvalCacheRepository
import io.deepsearch.infrastructure.database.PdfSourceEvalCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed/R2DBC implementation of the PDF source evaluation cache repository.
 */
@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class ExposedPdfSourceEvalCacheRepository(
    private val table: PdfSourceEvalCacheTable,
    private val transactionService: ITransactionService
) : IPdfSourceEvalCacheRepository {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun findByHash(contentHash: ByteArray): PdfSourceEvalCache? = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(contentHash)
            table.selectAll()
                .where { table.contentHash eq hashBase64 }
                .map { mapRowToCache(it) }
                .singleOrNull()
        }

    override suspend fun upsert(cache: PdfSourceEvalCache): Unit = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(cache.contentHash)

            table.upsert(
                keys = arrayOf(table.contentHash)
            ) {
                it[contentHash] = hashBase64
                it[evaluatedSourceJson] = cache.evaluatedSourceJson
                it[createdAtEpochMs] = cache.createdAt.toEpochMilliseconds()
                it[updatedAtEpochMs] = cache.updatedAt.toEpochMilliseconds()
                it[version] = cache.version
            }
            
            logger.debug("Cached PDF source eval result: hash={}", hashBase64.take(16))
        }

    private fun mapRowToCache(row: ResultRow): PdfSourceEvalCache {
        return PdfSourceEvalCache(
            contentHash = Base64.decode(row[table.contentHash]),
            evaluatedSourceJson = row[table.evaluatedSourceJson],
            createdAt = Instant.fromEpochMilliseconds(row[table.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[table.updatedAtEpochMs]),
            version = row[table.version]
        )
    }
}
