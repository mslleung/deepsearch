package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.WebpageExtraction
import io.deepsearch.domain.repositories.IWebpageExtractionRepository
import io.deepsearch.infrastructure.database.WebpageExtractionTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ExposedWebpageExtractionRepository : IWebpageExtractionRepository {

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun upsert(extraction: WebpageExtraction) = suspendTransaction {
        val hashBase64 = Base64.encode(extraction.webpageHtmlHash)

        // Try update first; if nothing updated, insert
        val updated = WebpageExtractionTable.update({ WebpageExtractionTable.webpageHtmlHash eq hashBase64 }) {
            it[extractedMarkdown] = extraction.extractedMarkdown
            it[updatedAtEpochMs] = extraction.updatedAtEpochMs
        }

        if (updated == 0) {
            WebpageExtractionTable.insert {
                it[webpageHtmlHash] = hashBase64
                it[extractedMarkdown] = extraction.extractedMarkdown
                it[createdAtEpochMs] = extraction.createdAtEpochMs
                it[updatedAtEpochMs] = extraction.updatedAtEpochMs
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun findByHash(webpageHtmlHash: ByteArray): WebpageExtraction? = suspendTransaction {
        val hashBase64 = Base64.encode(webpageHtmlHash)
        WebpageExtractionTable.selectAll()
            .where { WebpageExtractionTable.webpageHtmlHash eq hashBase64 }
            .map { mapRowToWebpageExtraction(it) }
            .singleOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun mapRowToWebpageExtraction(row: ResultRow): WebpageExtraction {
        return WebpageExtraction(
            webpageHtmlHash = Base64.decode(row[WebpageExtractionTable.webpageHtmlHash]),
            extractedMarkdown = row[WebpageExtractionTable.extractedMarkdown],
            createdAtEpochMs = row[WebpageExtractionTable.createdAtEpochMs],
            updatedAtEpochMs = row[WebpageExtractionTable.updatedAtEpochMs],
        )
    }
}

