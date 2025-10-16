package io.deepsearch.domain.models.entities

data class PdfMarkdown(
    val pdfHash: String, // SHA-256 hash of PDF bytes
    val markdown: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

