package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for caching hidden container analysis results (tables and mobile layouts).
 */
class HiddenContainerTableCacheTable : Table("hidden_container_table_cache") {
    /** SHA-256 hash of structural HTML (Base64 encoded) */
    val structuralHtmlHash = varchar("structural_html_hash", length = 128)
    
    /** Whether this container has any tables */
    val hasTables = bool("has_tables")
    
    /** JSON array of detected tables (CachedHiddenTable objects) */
    val tablesJson = text("tables_json")
    
    /** Whether this container has any mobile layouts */
    val hasMobileLayouts = bool("has_mobile_layouts").default(false)
    
    /** JSON array of detected mobile layouts (CachedHiddenMobileLayout objects) */
    val mobileLayoutsJson = text("mobile_layouts_json").default("[]")
    
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        index(true, structuralHtmlHash)
    }

    override val primaryKey = PrimaryKey(structuralHtmlHash)
}
