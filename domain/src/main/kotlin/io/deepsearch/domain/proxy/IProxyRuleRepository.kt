package io.deepsearch.domain.proxy

import io.deepsearch.domain.models.valueobjects.ProxyRuleId
import io.deepsearch.domain.models.valueobjects.UserId

/**
 * Repository interface for ProxyRule entities.
 */
interface IProxyRuleRepository {
    /**
     * Save a new proxy rule.
     *
     * @param proxyRule The rule to save
     * @return The saved rule with its assigned ID
     */
    suspend fun save(proxyRule: ProxyRule): ProxyRule

    /**
     * Find a proxy rule by its ID.
     *
     * @param id The rule ID
     * @return The rule if found, null otherwise
     */
    suspend fun findById(id: ProxyRuleId): ProxyRule?

    /**
     * Find all proxy rules for a user.
     *
     * @param userId The user ID
     * @return List of proxy rules owned by the user
     */
    suspend fun findByUserId(userId: UserId): List<ProxyRule>

    /**
     * Find a proxy rule by user ID and URL pattern.
     *
     * @param userId The user ID
     * @param urlPattern The URL pattern to search for
     * @return The rule if found, null otherwise
     */
    suspend fun findByUserIdAndUrlPattern(userId: UserId, urlPattern: String): ProxyRule?

    /**
     * Update an existing proxy rule.
     *
     * @param proxyRule The rule to update (must have a valid ID)
     * @return The updated rule
     */
    suspend fun update(proxyRule: ProxyRule): ProxyRule

    /**
     * Delete a proxy rule by its ID.
     *
     * @param id The rule ID to delete
     * @return true if the rule was deleted, false if not found
     */
    suspend fun delete(id: ProxyRuleId): Boolean

    /**
     * Check if a user owns a specific proxy rule.
     *
     * @param id The rule ID
     * @param userId The user ID
     * @return true if the user owns the rule
     */
    suspend fun isOwnedBy(id: ProxyRuleId, userId: UserId): Boolean

    /**
     * Count the number of proxy rules for a user.
     *
     * @param userId The user ID
     * @return The count of rules
     */
    suspend fun countByUserId(userId: UserId): Long
}
