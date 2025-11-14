package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.database.types.vector
import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

class WebpageMarkdownCacheEmbeddingTable(
    private val databaseCryptoService: IDatabaseCryptoService,
    private val webpageMarkdownCacheTable: WebpageMarkdownCacheTable
) : Table("webpage_markdown_embeddings") {
    val url = varchar("url", length = 2048).references(webpageMarkdownCacheTable.url, onDelete = ReferenceOption.CASCADE)
    val embedding = vector("embedding", dimensions = 1536) // gemini-embedding-001 produces 1536-dim vectors
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index on URL (also serves as primary key)
        index(true, url)
    }

    override val primaryKey = PrimaryKey(url)
}

