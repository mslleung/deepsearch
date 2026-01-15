package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageImage
import io.deepsearch.domain.repositories.IWebpageImageRepository
import io.deepsearch.infrastructure.database.WebpageImageCacheTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageImageRepository(
    private val webpageImageTable: WebpageImageCacheTable,
    private val transactionService: ITransactionService
) : IWebpageImageRepository {

    override suspend fun upsert(image: WebpageImage): Unit = transactionService.withTransaction {
        val hashBase64 = Base64.encode(image.imageBytesHash)

        webpageImageTable.upsert(
            keys = arrayOf(webpageImageTable.imageBytesHash)
        ) {
            it[imageBytesHash] = hashBase64
            it[gcsPath] = image.gcsPath
            it[mimeType] = image.mimeType
            it[extractedText] = image.extractedText
            it[createdAtEpochMs] = image.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = image.updatedAt.toEpochMilliseconds()
            it[version] = image.version
        }
    }

    override suspend fun batchUpsert(images: List<WebpageImage>): Unit = transactionService.withTransaction {
        if (images.isEmpty()) return@withTransaction

        // Sort by hash to ensure consistent lock ordering and prevent deadlocks
        // when multiple concurrent transactions upsert overlapping keys
        val sortedImages = images.sortedBy { Base64.encode(it.imageBytesHash) }

        webpageImageTable.batchUpsert(
            data = sortedImages,
            keys = arrayOf(webpageImageTable.imageBytesHash)
        ) { image ->
            val hashBase64 = Base64.encode(image.imageBytesHash)
            this[webpageImageTable.imageBytesHash] = hashBase64
            this[webpageImageTable.gcsPath] = image.gcsPath
            this[webpageImageTable.mimeType] = image.mimeType
            this[webpageImageTable.extractedText] = image.extractedText
            this[webpageImageTable.createdAtEpochMs] = image.createdAt.toEpochMilliseconds()
            this[webpageImageTable.updatedAtEpochMs] = image.updatedAt.toEpochMilliseconds()
            this[webpageImageTable.version] = image.version
        }
    }

    override suspend fun findByHash(imageBytesHash: ByteArray): WebpageImage? = transactionService.withTransaction {
        val hashBase64 = Base64.encode(imageBytesHash)
        webpageImageTable.selectAll()
            .where { webpageImageTable.imageBytesHash eq hashBase64 }
            .map { mapRowToWebpageImage(it) }
            .singleOrNull()
    }

    override suspend fun findByHashes(imageHashes: List<ByteArray>): List<WebpageImage> = transactionService.withTransaction {
        if (imageHashes.isEmpty()) return@withTransaction emptyList()
        
        val hashesBase64 = imageHashes.map { Base64.encode(it) }
        webpageImageTable.selectAll()
            .where { webpageImageTable.imageBytesHash inList hashesBase64 }
            .map { mapRowToWebpageImage(it) }
            .toList()
    }

    private fun mapRowToWebpageImage(row: ResultRow): WebpageImage {
        return WebpageImage(
            imageBytesHash = Base64.decode(row[webpageImageTable.imageBytesHash]),
            gcsPath = row[webpageImageTable.gcsPath],
            mimeType = row[webpageImageTable.mimeType],
            extractedText = row[webpageImageTable.extractedText],
            createdAt = Instant.fromEpochMilliseconds(row[webpageImageTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageImageTable.updatedAtEpochMs]),
            version = row[webpageImageTable.version]
        )
    }
}
