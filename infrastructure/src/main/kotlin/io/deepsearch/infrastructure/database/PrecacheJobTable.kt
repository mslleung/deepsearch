package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

object PrecacheJobTable : Table("precache_jobs") {
    val id = long("id").autoIncrement()
    val baseUrl = varchar("base_url", length = 2048)
    val maxUrlCount = integer("max_url_count")
    val processedCount = integer("processed_count")
    val state = varchar("state", length = 32)
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")

    init {
        index(false, baseUrl)
    }

    override val primaryKey = PrimaryKey(id)
}


