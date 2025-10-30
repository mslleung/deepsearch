package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.infrastructure.database.WebpageImageTable
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
class ExposedWebpageImageRepository : IWebpageImageRepository {

    override suspend fun upsert(image: WebpageImage): Unit = suspendTransaction {
        val hashBase64 = Base64.encode(image.imageBytesHash)

        WebpageImageTable.upsert(
            keys = arrayOf(WebpageImageTable.imageBytesHash)
        ) {
            it[imageBytesHash] = hashBase64
            it[extractedText] = image.extractedText
            it[createdAtEpochMs] = image.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = image.updatedAt.toEpochMilliseconds()
            it[version] = image.version
        }
    }

    override suspend fun findByHash(imageBytesHash: ByteArray): WebpageImage? = suspendTransaction {
        val hashBase64 = Base64.encode(imageBytesHash)
        WebpageImageTable.selectAll()
            .where { WebpageImageTable.imageBytesHash eq hashBase64 }
            .map { mapRowToWebpageImage(it) }
            .singleOrNull()
    }

    private fun mapRowToWebpageImage(row: ResultRow): WebpageImage {
        return WebpageImage(
            imageBytesHash = Base64.decode(row[WebpageImageTable.imageBytesHash]),
            extractedText = row[WebpageImageTable.extractedText],
            createdAt = Instant.fromEpochMilliseconds(row[WebpageImageTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[WebpageImageTable.updatedAtEpochMs]),
            version = row[WebpageImageTable.version]
        )
    }
}
