package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for caching website context per URL.
 * Used to store extracted context (title, description, summary) for faster query processing.
 */
class WebsiteContextTable : Table("website_context") {
    val url = varchar("url", length = 2048)
    val title = varchar("title", length = 500).nullable()
    val description = text("description").nullable()
    val contentSummary = text("content_summary").nullable()
    val cachedAtEpochMs = long("cached_at_epoch_ms")

    init {
        // Unique index on URL
        index(true, url)
    }

    override val primaryKey = PrimaryKey(url)
}

