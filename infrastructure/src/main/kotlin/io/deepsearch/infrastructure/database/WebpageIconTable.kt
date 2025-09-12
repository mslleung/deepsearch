package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageIconTable : Table("webpage_icons") {
    val id = integer("id").autoIncrement()
    val pageUrl = varchar("page_url", length = 2048)
    val selector = varchar("selector", length = 1024)
    val imageHash = varchar("image_hash", length = 128)
    val mimeType = varchar("mime_type", length = 32)
    // Store JPEG as base64 text to simplify cross-db binary handling in R2DBC Exposed v1
    val jpegBase64 = text("jpeg_base64")
    // Hints serialized as JSON array string
    val hintsJson = text("hints_json").default("[]")
    val label = varchar("label", length = 256)
    val confidence = double("confidence").default(0.0)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index per (page, selector, visual hash)
        index(true, pageUrl, selector, imageHash)
        // Helpful non-unique indexes
        index(false, pageUrl)
        index(false, imageHash)
    }

    override val primaryKey = PrimaryKey(id)
}