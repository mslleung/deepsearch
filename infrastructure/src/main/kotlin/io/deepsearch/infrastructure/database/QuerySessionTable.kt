package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class QuerySessionTable(
    private val databaseCryptoService: IDatabaseCryptoService,
    private val apiKeyTable: ApiKeyTable
) : Table("query_sessions") {
    val id = varchar("id", 255)
    val query = text("query")
    val url = varchar("url", 2048)
    val apiKeyId = integer("api_key_id").references(apiKeyTable.id)
    val searchMode = varchar("search_mode", 50).default("LIVE_CRAWLING")
    val finishReason = varchar("finish_reason", 50).nullable()
    val budgetTimeLimitMs = long("budget_time_limit_ms")
    val budgetMaxLinks = integer("budget_max_links")
    val answer = text("answer").nullable()
    val answerFound = bool("answer_found").nullable() // Whether a meaningful answer was found
    val imageIds = text("image_ids").default("[]") // JSON array of image IDs
    val durationMs = long("duration_ms").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    override val primaryKey = PrimaryKey(id)
}

