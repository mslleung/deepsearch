package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object QuerySessionTable : Table("query_sessions") {
    val id = varchar("id", 255)
    val query = text("query")
    val url = varchar("url", 2048)
    val state = varchar("state", 50)
    val finishReason = varchar("finish_reason", 50).nullable()
    val answerComplete = bool("answer_complete")
    val answer = text("answer").nullable()
    val traversedUrls = text("traversed_urls")  // JSON array
    val sourcesDiscovered = text("sources_discovered")  // JSON array
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)
}

