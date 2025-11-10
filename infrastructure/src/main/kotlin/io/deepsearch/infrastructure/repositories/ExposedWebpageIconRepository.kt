package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageIcon
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.infrastructure.database.WebpageIconCacheTable
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageIconRepository(
    private val webpageIconTable: WebpageIconCacheTable,
    private val transactionService: TransactionService
) : IWebpageIconRepository {

    override suspend fun upsert(icon: WebpageIcon): Unit = transactionService.withTransaction {
        val hashBase64 = Base64.encode(icon.imageBytesHash)

        webpageIconTable.upsert(
            keys = arrayOf(webpageIconTable.imageBytesHash)
        ) {
            it[imageBytesHash] = hashBase64
            it[label] = icon.label
            it[createdAtEpochMs] = icon.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = icon.updatedAt.toEpochMilliseconds()
            it[version] = icon.version
        }
    }

    override suspend fun batchUpsert(icons: List<WebpageIcon>): Unit = transactionService.withTransaction {
        if (icons.isEmpty()) return@withTransaction

        webpageIconTable.batchUpsert(
            data = icons,
            keys = arrayOf(webpageIconTable.imageBytesHash)
        ) { icon ->
            val hashBase64 = Base64.encode(icon.imageBytesHash)
            this[webpageIconTable.imageBytesHash] = hashBase64
            this[webpageIconTable.label] = icon.label
            this[webpageIconTable.createdAtEpochMs] = icon.createdAt.toEpochMilliseconds()
            this[webpageIconTable.updatedAtEpochMs] = icon.updatedAt.toEpochMilliseconds()
            this[webpageIconTable.version] = icon.version
        }
    }

    override suspend fun findByHash(imageBytesHash: ByteArray): WebpageIcon? = transactionService.withTransaction {
        val hashBase64 = Base64.encode(imageBytesHash)
        webpageIconTable.selectAll()
            .where { webpageIconTable.imageBytesHash eq hashBase64 }
            .map { mapRowToWebpageIcon(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageIcon(row: ResultRow): WebpageIcon {
        return WebpageIcon(
            imageBytesHash = Base64.decode(row[webpageIconTable.imageBytesHash]),
            label = row[webpageIconTable.label],
            createdAt = Instant.fromEpochMilliseconds(row[webpageIconTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageIconTable.updatedAtEpochMs]),
            version = row[webpageIconTable.version]
        )
    }
}
