package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.BypassStrategy
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Entity representing the learned bypass strategy for a specific domain.
 * 
 * The strategy is learned automatically:
 * - All domains start with DIRECT strategy
 * - If blocking is detected (403, captcha, Cloudflare), upgrade to FREE_ROTATING_PROXY
 * 
 * @property id The unique identifier (null if not yet persisted)
 * @property domain The domain name (e.g., "example.com")
 * @property strategy The current bypass strategy for this domain
 * @property lastBlockedAt When blocking was last detected (null if never blocked)
 * @property lastSuccessAt When last successful access occurred (null if never succeeded)
 * @property consecutiveFailures Number of consecutive failures (reset on success)
 * @property createdAt When this record was created
 * @property updatedAt When this record was last updated
 */
@OptIn(ExperimentalTime::class)
data class DomainBypassStrategy(
    val id: Long? = null,
    val domain: String,
    val strategy: BypassStrategy = BypassStrategy.DIRECT,
    val lastBlockedAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val consecutiveFailures: Int = 0,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
) {
    init {
        require(domain.isNotBlank()) { "Domain cannot be blank" }
        require(domain.length <= 255) { "Domain must be 255 characters or less" }
        require(consecutiveFailures >= 0) { "Consecutive failures cannot be negative" }
    }

    /**
     * Create an updated copy after a successful access.
     */
    fun recordSuccess(): DomainBypassStrategy {
        return copy(
            lastSuccessAt = Clock.System.now(),
            consecutiveFailures = 0,
            updatedAt = Clock.System.now()
        )
    }

    /**
     * Create an updated copy after blocking is detected.
     * Upgrades strategy to FREE_ROTATING_PROXY if currently DIRECT.
     */
    fun recordBlocked(): DomainBypassStrategy {
        val now = Clock.System.now()
        return copy(
            strategy = BypassStrategy.FREE_ROTATING_PROXY,
            lastBlockedAt = now,
            consecutiveFailures = consecutiveFailures + 1,
            updatedAt = now
        )
    }

    /**
     * Create an updated copy after a failure (not necessarily blocking).
     */
    fun recordFailure(): DomainBypassStrategy {
        return copy(
            consecutiveFailures = consecutiveFailures + 1,
            updatedAt = Clock.System.now()
        )
    }

    companion object {
        /**
         * Create a new strategy for a domain with default DIRECT access.
         */
        fun newForDomain(domain: String): DomainBypassStrategy {
            return DomainBypassStrategy(domain = domain)
        }

        /**
         * Extract domain from a URL.
         */
        fun extractDomain(url: String): String? {
            return try {
                val regex = Regex("^https?://([^/:]+)")
                regex.find(url)?.groupValues?.get(1)?.lowercase()
            } catch (e: Exception) {
                null
            }
        }
    }
}

