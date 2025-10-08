package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object QueryAnswerTable : Table("query_answers") {
    val queryHash = varchar("query_hash", length = 128)
    val answer = text("answer")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    init {
        // Unique index
        index(true, queryHash)
    }

    override val primaryKey = PrimaryKey(queryHash)
}
