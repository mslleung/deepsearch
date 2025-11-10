package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.PdfMarkdown
import io.deepsearch.domain.repositories.IPdfMarkdownRepository
import io.deepsearch.infrastructure.database.PdfMarkdownCacheTable
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedPdfMarkdownRepository(
    private val pdfMarkdownTable: PdfMarkdownCacheTable,
    private val transactionService: TransactionService
) : IPdfMarkdownRepository {

    override suspend fun findByHash(pdfHash: String): PdfMarkdown? = transactionService.withTransaction {
        pdfMarkdownTable.selectAll()
            .where { pdfMarkdownTable.pdfHash eq pdfHash }
            .map { mapRowToPdfMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(pdfMarkdown: PdfMarkdown): Unit = transactionService.withTransaction {
        pdfMarkdownTable.upsert(
            keys = arrayOf(pdfMarkdownTable.pdfHash)
        ) {
            it[pdfHash] = pdfMarkdown.pdfHash
            it[markdown] = pdfMarkdown.markdown
            it[pageCount] = pdfMarkdown.pageCount
            it[fileSizeBytes] = pdfMarkdown.fileSizeBytes
            it[createdAtEpochMs] = pdfMarkdown.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = pdfMarkdown.updatedAt.toEpochMilliseconds()
            it[version] = pdfMarkdown.version
        }
    }

    private fun mapRowToPdfMarkdown(row: ResultRow): PdfMarkdown {
        return PdfMarkdown(
            pdfHash = row[pdfMarkdownTable.pdfHash],
            markdown = row[pdfMarkdownTable.markdown],
            pageCount = row[pdfMarkdownTable.pageCount],
            fileSizeBytes = row[pdfMarkdownTable.fileSizeBytes],
            createdAt = Instant.fromEpochMilliseconds(row[pdfMarkdownTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[pdfMarkdownTable.updatedAtEpochMs]),
            version = row[pdfMarkdownTable.version]
        )
    }
}
