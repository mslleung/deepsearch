package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.infrastructure.database.WebpageImageTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64

class ExposedWebpageImageRepository : IWebpageImageRepository {

    override suspend fun upsert(image: WebpageImage) = suspendTransaction {
        val hashBase64 = Base64.encode(image.imageBytesHash)

        // Try update first; if nothing updated, insert
        val updated = WebpageImageTable.update({ WebpageImageTable.imageBytesHash eq hashBase64 }) {
            it[extractedText] = image.extractedText
            it[updatedAtEpochMs] = image.updatedAtEpochMs
        }

        if (updated == 0) {
            WebpageImageTable.insert {
                it[imageBytesHash] = hashBase64
                it[extractedText] = image.extractedText
                it[createdAtEpochMs] = image.createdAtEpochMs
                it[updatedAtEpochMs] = image.updatedAtEpochMs
            }
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
            createdAtEpochMs = row[WebpageImageTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageImageTable.updatedAtEpochMs]
        )
    }
}
