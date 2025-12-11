package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for proxy rules.
 * Each rule defines how traffic to matching URLs should be proxied.
 */
class ProxyRuleTable(
    private val userTable: UserTable
) : Table("proxy_rules") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id").references(userTable.id)
    val urlPattern = varchar("url_pattern", length = 255)
    val proxyType = varchar("proxy_type", length = 20) // NONE, CUSTOM, INCLUDED
    val customProxyUrl = varchar("custom_proxy_url", length = 500).nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient lookups by user
        index(isUnique = false, userId)
        // Unique constraint on user + url pattern to prevent duplicates
        uniqueIndex(userId, urlPattern)
    }
}
