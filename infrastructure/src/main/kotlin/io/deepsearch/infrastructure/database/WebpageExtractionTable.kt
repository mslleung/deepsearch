package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageExtractionTable : Table("webpage_extractions") {
    val webpageHtmlHash = varchar("webpage_html_hash", length = 128)
    val extractedMarkdown = text("extracted_markdown")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index
        index(true, webpageHtmlHash)
    }

    override val primaryKey = PrimaryKey(webpageHtmlHash)
}

