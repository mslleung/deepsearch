package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpagePopupTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("webpage_popups") {
    val pageHash = varchar("page_hash", length = 128)
    val popupXPaths = text("popup_xpaths") // JSON array of XPath strings
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index
        index(true, pageHash)
    }

    override val primaryKey = PrimaryKey(pageHash)
}
