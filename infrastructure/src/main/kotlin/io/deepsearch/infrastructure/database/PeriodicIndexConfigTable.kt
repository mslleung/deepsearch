package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

class PeriodicIndexConfigTable : Table("periodic_index_configs") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id")
    val url = varchar("url", length = 2048)
    val sitemapUrl = varchar("sitemap_url", length = 2048).nullable()
    val periodDays = integer("period_days").nullable()
    val maxUrlCount = integer("max_url_count").default(100)
    val enabled = bool("enabled").default(true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastRunAt = long("last_run_at").nullable()
    val version = long("version").default(0)
    val languagePattern = varchar("language_pattern", length = 64).nullable()
    val ocrLanguage = varchar("ocr_language", length = 16)

    override val primaryKey = PrimaryKey(id)
    
    init {
        // Non-unique index on userId for query performance (users can have multiple configs)
        index(isUnique = false, userId)
    }
}
