package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for tracking file search stores per domain.
 * 
 * Each domain gets its own Gemini File Search Store. This table maps
 * domains to their corresponding Gemini store resource names.
 */
class FileSearchStoreTable : Table("file_search_stores") {
    val id = long("id").autoIncrement()
    val domain = varchar("domain", length = 512)
    val geminiStoreName = varchar("gemini_store_name", length = 512)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        // Unique index on domain - each domain has exactly one store
        index(isUnique = true, domain)
        // Index on Gemini store name for reverse lookups
        index(isUnique = true, geminiStoreName)
    }
}

