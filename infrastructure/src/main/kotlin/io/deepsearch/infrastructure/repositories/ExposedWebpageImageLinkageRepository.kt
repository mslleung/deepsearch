package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageImageLinkage
import io.deepsearch.domain.repositories.IWebpageImageLinkageRepository
import io.deepsearch.infrastructure.database.WebpageImageLinkageTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedWebpageImageLinkageRepository(
    private val webpageImageLinkageTable: WebpageImageLinkageTable,
    private val transactionService: ITransactionService
) : IWebpageImageLinkageRepository {

    override suspend fun upsertLinkages(url: String, imageHashes: List<ByteArray>): Unit = transactionService.withTransaction {
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Mark all existing linkages for this URL as inactive
        webpageImageLinkageTable.update(
            where = { webpageImageLinkageTable.url eq url }
        ) {
            it[isActive] = false
            it[updatedAtEpochMs] = now
        }
        
        // Upsert the new linkages as active
        if (imageHashes.isNotEmpty()) {
            webpageImageLinkageTable.batchUpsert(
                data = imageHashes,
                keys = arrayOf(webpageImageLinkageTable.url, webpageImageLinkageTable.imageBytesHash)
            ) { hash ->
                val hashBase64 = Base64.encode(hash)
                this[webpageImageLinkageTable.url] = url
                this[webpageImageLinkageTable.imageBytesHash] = hashBase64
                this[webpageImageLinkageTable.isActive] = true
                this[webpageImageLinkageTable.createdAtEpochMs] = now
                this[webpageImageLinkageTable.updatedAtEpochMs] = now
            }
        }
    }

    override suspend fun findActiveByUrl(url: String): List<ByteArray> = transactionService.withTransaction {
        webpageImageLinkageTable.selectAll()
            .where { (webpageImageLinkageTable.url eq url) and (webpageImageLinkageTable.isActive eq true) }
            .map { Base64.decode(it[webpageImageLinkageTable.imageBytesHash]) }
            .toList()
    }

    override suspend fun findByUrlAndHash(url: String, imageBytesHash: ByteArray): WebpageImageLinkage? = 
        transactionService.withTransaction {
            val hashBase64 = Base64.encode(imageBytesHash)
            webpageImageLinkageTable.selectAll()
                .where { 
                    (webpageImageLinkageTable.url eq url) and 
                    (webpageImageLinkageTable.imageBytesHash eq hashBase64) 
                }
                .map { mapRowToLinkage(it) }
                .singleOrNull()
        }

    private fun mapRowToLinkage(row: ResultRow): WebpageImageLinkage {
        return WebpageImageLinkage(
            url = row[webpageImageLinkageTable.url],
            imageBytesHash = Base64.decode(row[webpageImageLinkageTable.imageBytesHash]),
            isActive = row[webpageImageLinkageTable.isActive],
            createdAt = Instant.fromEpochMilliseconds(row[webpageImageLinkageTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[webpageImageLinkageTable.updatedAtEpochMs])
        )
    }
}


