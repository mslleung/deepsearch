package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.database.types.tsvector
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpageMarkdownCacheTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("webpage_markdowns") {
    val url = varchar("url", length = 2048)
    val markdown = text("markdown").nullable()
    val html = text("html").nullable()
    val httpStatus = integer("http_status").nullable()
    val httpReason = text("http_reason").nullable()
    val mimeType = varchar("mime_type", length = 256).nullable()
    val markdownSearchVector = tsvector("markdown_search_vector").nullable() // tsvector for full-text search
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index on URL
        index(true, url)
        // Note: HNSW index for vector similarity search is created in DatabaseConfigurationService
    }

    override val primaryKey = PrimaryKey(url)
}

