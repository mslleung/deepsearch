package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageIconTable : Table("webpage_icons") {
    val imageBytesHash = varchar("image_bytes_hash", length = 128)
    val label = varchar("label", length = 256).nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index
        index(true, imageBytesHash)
    }

    override val primaryKey = PrimaryKey(imageBytesHash)
}