package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.DomainBypassStrategy
import io.deepsearch.domain.models.valueobjects.BypassStrategy

/**
 * Repository interface for domain bypass strategies.
 * 
 * Strategies are stored globally (not per-user) so all users benefit from
 * learned bypass patterns for each domain.
 */
interface IDomainBypassStrategyRepository {

    /**
     * Get the bypass strategy for a domain.
     * Returns null if no strategy has been recorded (domain is new).
     * 
     * @param domain The domain name (e.g., "example.com")
     * @return The domain bypass strategy or null if not found
     */
    suspend fun findByDomain(domain: String): DomainBypassStrategy?

    /**
     * Get the bypass strategy for a domain, creating a default if not found.
     * 
     * @param domain The domain name
     * @return The existing or newly created domain bypass strategy
     */
    suspend fun getOrCreate(domain: String): DomainBypassStrategy

    /**
     * Save or update a domain bypass strategy.
     * 
     * @param strategy The strategy to save
     * @return The saved strategy with ID populated
     */
    suspend fun save(strategy: DomainBypassStrategy): DomainBypassStrategy

    /**
     * Update the strategy for a domain.
     * Creates the domain record if it doesn't exist.
     * 
     * @param domain The domain name
     * @param newStrategy The new strategy to set
     * @return The updated domain bypass strategy
     */
    suspend fun updateStrategy(domain: String, newStrategy: BypassStrategy): DomainBypassStrategy

    /**
     * Record a successful access for a domain.
     * Resets consecutive failure count.
     * 
     * @param domain The domain name
     * @return The updated domain bypass strategy
     */
    suspend fun recordSuccess(domain: String): DomainBypassStrategy

    /**
     * Record that a domain blocked access.
     * Upgrades strategy to FREE_ROTATING_PROXY and increments failure count.
     * 
     * @param domain The domain name
     * @return The updated domain bypass strategy
     */
    suspend fun recordBlocked(domain: String): DomainBypassStrategy

    /**
     * Get all domains that use a specific strategy.
     * 
     * @param strategy The strategy to filter by
     * @return List of domain bypass strategies using the specified strategy
     */
    suspend fun findByStrategy(strategy: BypassStrategy): List<DomainBypassStrategy>

    /**
     * Get statistics about bypass strategies.
     * 
     * @return Statistics object with counts
     */
    suspend fun getStatistics(): BypassStrategyStatistics
}

/**
 * Statistics about bypass strategies.
 */
data class BypassStrategyStatistics(
    val totalDomains: Long,
    val directDomains: Long,
    val proxyDomains: Long,
    val domainsBlockedLast24h: Long
)

