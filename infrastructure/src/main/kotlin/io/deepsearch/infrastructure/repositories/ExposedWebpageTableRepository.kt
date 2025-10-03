package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageTable
import io.deepsearch.domain.repositories.IWebpageTableRepository
import io.deepsearch.infrastructure.database.WebpageTableTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64

class ExposedWebpageTableRepository : IWebpageTableRepository {

    override suspend fun upsert(table: WebpageTable) = suspendTransaction {
        val hashBase64 = Base64.encode(table.fullPageScreenshotHash)

        // Try update first; if nothing updated, insert
        val updated = WebpageTableTable.update({ WebpageTableTable.fullPageScreenshotHash eq hashBase64 }) {
            it[tables] = table.tables
            it[updatedAtEpochMs] = table.updatedAtEpochMs
        }

        if (updated == 0) {
            WebpageTableTable.insert {
                it[fullPageScreenshotHash] = hashBase64
                it[tables] = table.tables
                it[createdAtEpochMs] = table.createdAtEpochMs
                it[updatedAtEpochMs] = table.updatedAtEpochMs
            }
        }
    }

    override suspend fun findByHash(fullPageScreenshotHash: ByteArray): WebpageTable? = suspendTransaction {
        val hashBase64 = Base64.encode(fullPageScreenshotHash)
        WebpageTableTable.selectAll()
            .where { WebpageTableTable.fullPageScreenshotHash eq hashBase64 }
            .map { mapRowToWebpageTable(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageTable(row: ResultRow): WebpageTable {
        return WebpageTable(
            fullPageScreenshotHash = Base64.decode(row[WebpageTableTable.fullPageScreenshotHash]),
            tables = row[WebpageTableTable.tables],
            createdAtEpochMs = row[WebpageTableTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageTableTable.updatedAtEpochMs],
        )
    }
}

