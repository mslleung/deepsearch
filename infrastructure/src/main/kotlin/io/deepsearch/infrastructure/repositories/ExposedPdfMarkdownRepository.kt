package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.PdfMarkdown
import io.deepsearch.domain.repositories.IPdfMarkdownRepository
import io.deepsearch.infrastructure.database.PdfMarkdownTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert

class ExposedPdfMarkdownRepository : IPdfMarkdownRepository {

    override suspend fun findByHash(pdfHash: String): PdfMarkdown? = suspendTransaction {
        PdfMarkdownTable.selectAll()
            .where { PdfMarkdownTable.pdfHash eq pdfHash }
            .map { mapRowToPdfMarkdown(it) }
            .singleOrNull()
    }

    override suspend fun upsert(pdfMarkdown: PdfMarkdown): Unit = suspendTransaction {
        PdfMarkdownTable.upsert(
            keys = arrayOf(PdfMarkdownTable.pdfHash)
        ) {
            it[pdfHash] = pdfMarkdown.pdfHash
            it[markdown] = pdfMarkdown.markdown
            it[pageCount] = pdfMarkdown.pageCount
            it[fileSizeBytes] = pdfMarkdown.fileSizeBytes
            it[createdAtEpochMs] = pdfMarkdown.createdAtEpochMs
            it[updatedAtEpochMs] = pdfMarkdown.updatedAtEpochMs
        }
    }

    private fun mapRowToPdfMarkdown(row: ResultRow): PdfMarkdown {
        return PdfMarkdown(
            pdfHash = row[PdfMarkdownTable.pdfHash],
            markdown = row[PdfMarkdownTable.markdown],
            pageCount = row[PdfMarkdownTable.pageCount],
            fileSizeBytes = row[PdfMarkdownTable.fileSizeBytes],
            createdAtEpochMs = row[PdfMarkdownTable.createdAtEpochMs],
            updatedAtEpochMs = row[PdfMarkdownTable.updatedAtEpochMs]
        )
    }
}

