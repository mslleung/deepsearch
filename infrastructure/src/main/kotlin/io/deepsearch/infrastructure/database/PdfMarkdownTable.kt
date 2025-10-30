package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object PdfMarkdownTable : Table("pdf_markdowns") {
    val pdfHash = varchar("pdf_hash", length = 64) // SHA-256 hex string
    val markdown = text("markdown")
    val pageCount = integer("page_count")
    val fileSizeBytes = long("file_size_bytes")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(1)

    init {
        // Unique index on PDF hash
        index(true, pdfHash)
    }

    override val primaryKey = PrimaryKey(pdfHash)
}

