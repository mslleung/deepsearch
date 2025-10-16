package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageMarkdownTable : Table("webpage_markdowns") {
    val url = varchar("url", length = 2048)
    val markdown = text("markdown")
    val html = text("html")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index on URL
        index(true, url)
    }

    override val primaryKey = PrimaryKey(url)
}

