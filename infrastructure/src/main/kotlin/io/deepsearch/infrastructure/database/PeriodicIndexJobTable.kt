package io.deepsearch.infrastructure.database

import io.deepsearch.infrastructure.services.IDatabaseCryptoService
import org.jetbrains.exposed.v1.core.Table

class PeriodicIndexJobTable(
    private val databaseCryptoService: IDatabaseCryptoService
) : Table("periodic_index_jobs") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id")
    val baseUrl = varchar("base_url", length = 2048)
    val maxUrlCount = integer("max_url_count")
    val sitemapUrl = varchar("sitemap_url", length = 2048).nullable()
    val processedCount = integer("processed_count")
    val state = varchar("state", length = 32)
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")
    val version = long("version").default(0)
    val languagePattern = varchar("language_pattern", length = 64).nullable()
    val ocrLanguage = varchar("ocr_language", length = 16)

    init {
        index(false, baseUrl)
        index(false, userId)
    }

    override val primaryKey = PrimaryKey(id)
}

