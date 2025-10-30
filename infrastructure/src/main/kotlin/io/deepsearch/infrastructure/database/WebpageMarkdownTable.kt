package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageMarkdownTable : Table("webpage_markdowns") {
    val url = varchar("url", length = 2048)
    val markdown = text("markdown").nullable()
    val html = text("html").nullable()
    val httpStatus = integer("http_status").nullable()
    val httpReason = varchar("http_reason", length = 256).nullable()
    val mimeType = varchar("mime_type", length = 256).nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(1)

    init {
        // Unique index on URL
        index(true, url)
    }

    override val primaryKey = PrimaryKey(url)
}

