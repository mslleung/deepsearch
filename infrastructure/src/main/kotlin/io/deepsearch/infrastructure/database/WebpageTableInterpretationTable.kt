package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class WebpageTableInterpretationTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("webpage_table_interpretations") {
    val tableDataHash = varchar("table_data_hash", length = 128)
    val markdown = text("markdown")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        // Unique index
        index(true, tableDataHash)
    }

    override val primaryKey = PrimaryKey(tableDataHash)
}

