package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.config.DatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpageSemanticElementTable(
    private val databaseCryptoService: DatabaseCryptoService
) : Table("webpage_navigation_elements") {
    val pageHash = varchar("page_hash", length = 128)
    // JSON column storing list of navigation elements with xpath, type, and note
    val elementsJson = text("elements_json")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index
        index(true, pageHash)
    }

    override val primaryKey = PrimaryKey(pageHash)
}

