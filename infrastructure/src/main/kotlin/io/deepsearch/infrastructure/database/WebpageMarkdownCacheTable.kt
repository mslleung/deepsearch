package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.database.types.tsvector
import io.deepsearch.infrastructure.database.types.vector
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpageMarkdownCacheTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("webpage_markdowns") {
    val url = varchar("url", length = 2048)
    val title = text("title").nullable()
    val description = text("description").nullable()
    val markdown = text("markdown").nullable()
    val markdownSanitized = text("markdown_sanitized").nullable() // Sanitized markdown for full-text search (no emoji/special chars)
    val html = text("html").nullable()
    val cleanedPreviewHtml = text("cleaned_preview_html").nullable() // Pre-cleaned HTML for preview path
    val httpStatus = integer("http_status").nullable()
    val httpReason = text("http_reason").nullable()
    val mimeType = varchar("mime_type", length = 256).nullable()
    val embedding = vector("embedding", dimensions = 1536).nullable() // gemini-embedding-001 produces 1536-dim vectors
    val markdownSearchVector = tsvector("markdown_search_vector").nullable() // tsvector for full-text search
    val isPreview = bool("is_preview").default(false) // true if content is from simple text extraction (no LLM)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index on URL
        index(true, url)
        // Note: HNSW index for vector similarity search and GIN index for full-text search are created in DatabaseConfigurationService
    }

    override val primaryKey = PrimaryKey(url)
}

