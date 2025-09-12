package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageIconTable : Table("webpage_icons") {
    val imageHash = varchar("image_hash", length = 128)
    val label = varchar("label", length = 256)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index
        index(true, imageHash)
    }

    override val primaryKey = PrimaryKey(imageHash)
}