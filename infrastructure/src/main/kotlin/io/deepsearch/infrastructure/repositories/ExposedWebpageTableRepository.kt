package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageTable
import io.deepsearch.domain.repositories.IWebpageTableRepository
import io.deepsearch.infrastructure.database.WebpageTableCacheTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageTableRepository(
    private val webpageTableTable: WebpageTableCacheTable
) : IWebpageTableRepository {

    override suspend fun upsert(table: WebpageTable): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(table.webpageHtmlHash)

        webpageTableTable.upsert(
            keys = arrayOf(webpageTableTable.webpageHtmlHash)
        ) {
            it[webpageHtmlHash] = hashBase64
            it[tables] = table.tables
            it[createdAtEpochMs] = table.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = table.updatedAt.toEpochMilliseconds()
            it[version] = table.version
        }
    }

    override suspend fun findByHash(webpageHtmlHash: ByteArray): WebpageTable? = suspendTransaction {
        val hashBase64 = Base64.encode(webpageHtmlHash)
        webpageTableTable.selectAll()
            .where { webpageTableTable.webpageHtmlHash eq hashBase64 }
            .map { mapRowToWebpageTable(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageTable(row: ResultRow): WebpageTable {
        return WebpageTable(
            webpageHtmlHash = Base64.decode(row[webpageTableTable.webpageHtmlHash]),
            tables = row[webpageTableTable.tables],
            createdAt = Instant.fromEpochMilliseconds(row[webpageTableTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageTableTable.updatedAtEpochMs]),
            version = row[webpageTableTable.version]
        )
    }
}
