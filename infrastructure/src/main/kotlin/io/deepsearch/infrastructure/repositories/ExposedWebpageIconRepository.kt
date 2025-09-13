package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.infrastructure.database.WebpageIconTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64

class ExposedWebpageIconRepository : IWebpageIconRepository {

    override suspend fun upsert(icon: WebpageIcon) = suspendTransaction {
        val hashBase64 = Base64.encode(icon.imageBytesHash)

        // Try update first; if nothing updated, insert
        val updated = WebpageIconTable.update({ WebpageIconTable.imageBytesHash eq hashBase64 }) {
            it[label] = icon.label
            it[updatedAtEpochMs] = icon.updatedAtEpochMs
        }

        if (updated == 0) {
            WebpageIconTable.insert {
                it[imageBytesHash] = hashBase64
                it[label] = icon.label
                it[createdAtEpochMs] = icon.createdAtEpochMs
                it[updatedAtEpochMs] = icon.updatedAtEpochMs
            }
        }
    }

    override suspend fun findByHash(imageBytesHash: ByteArray): WebpageIcon? = suspendTransaction {
        val hashBase64 = Base64.encode(imageBytesHash)
        WebpageIconTable.selectAll()
            .where { WebpageIconTable.imageBytesHash eq hashBase64 }
            .map { mapRowToWebpageIcon(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageIcon(row: ResultRow): WebpageIcon {
        return WebpageIcon(
            imageBytesHash = Base64.decode(row[WebpageIconTable.imageBytesHash]),
            label = row[WebpageIconTable.label],
            createdAtEpochMs = row[WebpageIconTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageIconTable.updatedAtEpochMs],
        )
    }
}