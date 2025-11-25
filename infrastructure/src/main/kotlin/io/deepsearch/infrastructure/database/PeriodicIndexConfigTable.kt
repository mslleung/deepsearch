package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

class PeriodicIndexConfigTable : Table("periodic_index_configs") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id")
    val url = varchar("url", length = 2048)
    val periodDays = integer("period_days").nullable()
    val enabled = bool("enabled").default(true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastRunAt = long("last_run_at").nullable()
    val version = long("version").default(0)

    override val primaryKey = PrimaryKey(id)
    
    init {
        uniqueIndex(userId)
    }
}
