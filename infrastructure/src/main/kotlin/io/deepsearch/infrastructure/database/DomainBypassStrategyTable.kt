package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for storing domain bypass strategies.
 * 
 * Strategies are global (not per-user) so all users benefit from learned
 * bypass patterns for each domain.
 */
class DomainBypassStrategyTable : Table("domain_bypass_strategies") {
    val id = long("id").autoIncrement()
    val domain = varchar("domain", 255).uniqueIndex()
    val strategy = varchar("strategy", 50)  // "DIRECT" or "FREE_ROTATING_PROXY"
    val lastBlockedAtEpochMs = long("last_blocked_at_epoch_ms").nullable()
    val lastSuccessAtEpochMs = long("last_success_at_epoch_ms").nullable()
    val consecutiveFailures = integer("consecutive_failures").default(0)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for filtering by strategy
        index(isUnique = false, strategy)
        
        // Index for finding recently blocked domains
        index(isUnique = false, lastBlockedAtEpochMs)
    }
}

