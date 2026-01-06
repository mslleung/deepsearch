package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.HtmlSourceEvalCache
import io.deepsearch.domain.repositories.IHtmlSourceEvalCacheRepository
import io.deepsearch.infrastructure.database.HtmlSourceEvalCacheTable
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
 * Exposed/R2DBC implementation of the HTML source evaluation cache repository.
 */
@OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)
class ExposedHtmlSourceEvalCacheRepository(
    private val table: HtmlSourceEvalCacheTable,
    private val transactionService: ITransactionService
) : IHtmlSourceEvalCacheRepository {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun findByHash(contentHash: ByteArray): HtmlSourceEvalCache? = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(contentHash)
            table.selectAll()
                .where { table.contentHash eq hashBase64 }
                .map { mapRowToCache(it) }
                .singleOrNull()
        }

    override suspend fun upsert(cache: HtmlSourceEvalCache): Unit = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(cache.contentHash)

            table.upsert(
                keys = arrayOf(table.contentHash)
            ) {
                it[contentHash] = hashBase64
                it[evaluatedSourceJson] = cache.evaluatedSourceJson
                it[promptTokens] = cache.promptTokens
                it[outputTokens] = cache.outputTokens
                it[totalTokens] = cache.totalTokens
                it[createdAtEpochMs] = cache.createdAt.toEpochMilliseconds()
                it[updatedAtEpochMs] = cache.updatedAt.toEpochMilliseconds()
                it[version] = cache.version
            }
            
            logger.debug("Cached HTML source eval result: hash={}", hashBase64.take(16))
        }

    private fun mapRowToCache(row: ResultRow): HtmlSourceEvalCache {
        return HtmlSourceEvalCache(
            contentHash = Base64.decode(row[table.contentHash]),
            evaluatedSourceJson = row[table.evaluatedSourceJson],
            promptTokens = row[table.promptTokens],
            outputTokens = row[table.outputTokens],
            totalTokens = row[table.totalTokens],
            createdAt = Instant.fromEpochMilliseconds(row[table.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[table.updatedAtEpochMs]),
            version = row[table.version]
        )
    }
}

