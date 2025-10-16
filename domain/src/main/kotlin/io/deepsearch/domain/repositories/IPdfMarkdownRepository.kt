package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.PdfMarkdown

interface IPdfMarkdownRepository {
    suspend fun findByHash(pdfHash: String): PdfMarkdown?
    suspend fun upsert(pdfMarkdown: PdfMarkdown)
}

