package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageImageTable : Table("webpage_images") {
    val imageBytesHash = varchar("image_bytes_hash", length = 128)
    val extractedText = text("extracted_text").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index
        index(true, imageBytesHash)
    }

    override val primaryKey = PrimaryKey(imageBytesHash)
}
