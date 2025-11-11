package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.database.types.vector
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpageMarkdownCacheTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("webpage_markdowns") {
    val url = varchar("url", length = 2048)
    val markdown = text("markdown").nullable()
    val html = text("html").nullable()
    val httpStatus = integer("http_status").nullable()
    val httpReason = varchar("http_reason", length = 256).nullable()
    val mimeType = varchar("mime_type", length = 256).nullable()
    val embedding = vector("embedding", dimensions = 1536).nullable() // gemini-embedding-001 produces 1536-dim vectors
    val markdownSearchVector = text("markdown_search_vector").nullable() // tsvector for full-text search
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

