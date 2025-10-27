package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.infrastructure.database.WebpageIconTable
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
class ExposedWebpageIconRepository : IWebpageIconRepository {

    override suspend fun upsert(icon: WebpageIcon): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(icon.imageBytesHash)

        WebpageIconTable.upsert(
            keys = arrayOf(WebpageIconTable.imageBytesHash)
        ) {
            it[imageBytesHash] = hashBase64
            it[label] = icon.label
            it[createdAtEpochMs] = icon.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = icon.updatedAt.toEpochMilliseconds()
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
            createdAt = Instant.fromEpochMilliseconds(row[WebpageIconTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[WebpageIconTable.updatedAtEpochMs]),
        )
    }
}