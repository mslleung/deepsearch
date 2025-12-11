package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.ProxyRuleId
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.proxy.IProxyRuleRepository
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.deepsearch.domain.proxy.ProxyRule
import io.deepsearch.domain.proxy.ProxyType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IProxySettingsService {
    /**
     * Create a new proxy rule for a user.
     *
     * @param userId The user creating the rule
     * @param urlPattern The URL pattern to match
     * @param proxyType The type of proxy to use
     * @param customProxyUrl The custom proxy URL (required for CUSTOM type)
     * @return The created proxy rule
     */
    suspend fun createRule(
        userId: UserId,
        urlPattern: String,
        proxyType: ProxyType,
        customProxyUrl: String? = null
    ): ProxyRule

    /**
     * Get all proxy rules for a user.
     *
     * @param userId The user ID
     * @return List of proxy rules
     */
    suspend fun getRules(userId: UserId): List<ProxyRule>

    /**
     * Get a specific proxy rule by ID.
     *
     * @param userId The user ID (for ownership verification)
     * @param ruleId The rule ID
     * @return The proxy rule if found and owned by user
     */
    suspend fun getRule(userId: UserId, ruleId: ProxyRuleId): ProxyRule?

    /**
     * Update an existing proxy rule.
     *
     * @param userId The user ID (for ownership verification)
     * @param ruleId The rule ID to update
     * @param urlPattern New URL pattern (optional)
     * @param proxyType New proxy type (optional)
     * @param customProxyUrl New custom proxy URL (optional)
     * @return The updated proxy rule
     */
    suspend fun updateRule(
        userId: UserId,
        ruleId: ProxyRuleId,
        urlPattern: String? = null,
        proxyType: ProxyType? = null,
        customProxyUrl: String? = null
    ): ProxyRule

    /**
     * Delete a proxy rule.
     *
     * @param userId The user ID (for ownership verification)
     * @param ruleId The rule ID to delete
     * @return true if deleted, false if not found
     */
    suspend fun deleteRule(userId: UserId, ruleId: ProxyRuleId): Boolean

    /**
     * Resolve the proxy configuration for a given URL based on user's rules.
     *
     * @param userId The user ID
     * @param url The URL to check
     * @return The resolved proxy configuration (None if no matching rule)
     */
    suspend fun resolveProxyForUrl(userId: UserId, url: String): ProxyConfiguration
}

@OptIn(ExperimentalTime::class)
class ProxySettingsService(
    private val proxyRuleRepository: IProxyRuleRepository
) : IProxySettingsService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        /**
         * Maximum number of proxy rules per user.
         */
        const val MAX_RULES_PER_USER = 50
    }

    override suspend fun createRule(
        userId: UserId,
        urlPattern: String,
        proxyType: ProxyType,
        customProxyUrl: String?
    ): ProxyRule {
        // Check if user has reached the maximum number of rules
        val currentCount = proxyRuleRepository.countByUserId(userId)
        if (currentCount >= MAX_RULES_PER_USER) {
            throw IllegalStateException(
                "Maximum number of proxy rules reached ($MAX_RULES_PER_USER). " +
                "Please delete some existing rules before creating new ones."
            )
        }

        // Check for duplicate URL pattern
        val existingRule = proxyRuleRepository.findByUserIdAndUrlPattern(userId, urlPattern)
        if (existingRule != null) {
            throw IllegalArgumentException("A rule with this URL pattern already exists")
        }

        val rule = ProxyRule(
            userId = userId,
            urlPattern = urlPattern,
            proxyType = proxyType,
            customProxyUrl = customProxyUrl
        )

        val savedRule = proxyRuleRepository.save(rule)
        logger.info("Created proxy rule {} for user {}: {} -> {}", savedRule.id, userId, urlPattern, proxyType)

        return savedRule
    }

    override suspend fun getRules(userId: UserId): List<ProxyRule> {
        return proxyRuleRepository.findByUserId(userId)
    }

    override suspend fun getRule(userId: UserId, ruleId: ProxyRuleId): ProxyRule? {
        val rule = proxyRuleRepository.findById(ruleId) ?: return null

        // Verify ownership
        if (rule.userId != userId) {
            logger.warn("User {} attempted to access rule {} owned by user {}", userId, ruleId, rule.userId)
            return null
        }

        return rule
    }

    override suspend fun updateRule(
        userId: UserId,
        ruleId: ProxyRuleId,
        urlPattern: String?,
        proxyType: ProxyType?,
        customProxyUrl: String?
    ): ProxyRule {
        val rule = proxyRuleRepository.findById(ruleId)
            ?: throw IllegalArgumentException("Proxy rule not found: $ruleId")

        // Verify ownership
        if (rule.userId != userId) {
            throw IllegalAccessError("Proxy rule $ruleId does not belong to user $userId")
        }

        // Check for duplicate URL pattern if changing it
        if (urlPattern != null && urlPattern != rule.urlPattern) {
            val existingRule = proxyRuleRepository.findByUserIdAndUrlPattern(userId, urlPattern)
            if (existingRule != null) {
                throw IllegalArgumentException("A rule with this URL pattern already exists")
            }
        }

        rule.update(
            urlPattern = urlPattern,
            proxyType = proxyType,
            customProxyUrl = customProxyUrl,
            updatedAt = Clock.System.now()
        )

        val updatedRule = proxyRuleRepository.update(rule)
        logger.info("Updated proxy rule {} for user {}", ruleId, userId)

        return updatedRule
    }

    override suspend fun deleteRule(userId: UserId, ruleId: ProxyRuleId): Boolean {
        // Verify ownership
        if (!proxyRuleRepository.isOwnedBy(ruleId, userId)) {
            logger.warn("User {} attempted to delete rule {} they don't own", userId, ruleId)
            return false
        }

        val deleted = proxyRuleRepository.delete(ruleId)
        if (deleted) {
            logger.info("Deleted proxy rule {} for user {}", ruleId, userId)
        }

        return deleted
    }

    override suspend fun resolveProxyForUrl(userId: UserId, url: String): ProxyConfiguration {
        val rules = proxyRuleRepository.findByUserId(userId)

        // Find the first matching rule
        // More specific patterns (without wildcard) take precedence over wildcards
        val matchingRule = rules
            .filter { it.matches(url) }
            .sortedBy { if (it.urlPattern.startsWith("*.")) 1 else 0 } // Exact matches first
            .firstOrNull()

        return if (matchingRule != null) {
            logger.debug("URL {} matched rule {}: {}", url, matchingRule.id, matchingRule.proxyType)
            ProxyConfiguration.fromRule(matchingRule)
        } else {
            ProxyConfiguration.None
        }
    }
}

