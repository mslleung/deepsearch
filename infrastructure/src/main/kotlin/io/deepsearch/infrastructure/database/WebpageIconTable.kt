package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpageIconTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("webpage_icons") {
    val imageBytesHash = varchar("image_bytes_hash", length = 128)
    val label = text("label").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index
        index(true, imageBytesHash)
    }

    override val primaryKey = PrimaryKey(imageBytesHash)
}