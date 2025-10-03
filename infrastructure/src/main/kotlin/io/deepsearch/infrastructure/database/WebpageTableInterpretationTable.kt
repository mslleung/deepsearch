package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object WebpageTableInterpretationTable : Table("webpage_table_interpretations") {
    val tableDataHash = varchar("table_data_hash", length = 128)
    val markdown = text("markdown")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index
        index(true, tableDataHash)
    }

    override val primaryKey = PrimaryKey(tableDataHash)
}

