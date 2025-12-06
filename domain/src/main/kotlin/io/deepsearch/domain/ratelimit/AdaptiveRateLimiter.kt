package io.deepsearch.domain.ratelimit

import io.deepsearch.domain.exceptions.HttpClientErrorException
import io.deepsearch.domain.exceptions.RateLimitExceededException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Adaptive rate limiter implementing AIMD (Additive Increase Multiplicative Decrease)
 * congestion control algorithm, similar to TCP.
 *
 * Each domain maintains its own concurrency limit that adapts based on HTTP 429 responses:
 * - On 429 (rate limited): Multiplicatively decrease concurrency by half
 * - On success: Additively increase concurrency by a small step
 *
 * This allows the crawler to:
 * 1. Quickly back off when rate limited (multiplicative decrease)
 * 2. Slowly probe for additional capacity (additive increase)
 * 3. Automatically retry failed requests with exponential backoff
 */
interface IAdaptiveRateLimiter {
    /**
     * Execute a block with adaptive rate limiting for the given URL.
     * Extracts the domain from the URL automatically.
     *
     * @param url The URL being accessed (domain is extracted for rate limiting)
     * @param block The suspending block to execute
     * @return The result of the block
     * @throws RateLimitExceededException if max retries are exhausted after 429s
     */
    suspend fun <T> withRateLimit(url: String, block: suspend () -> T): T

    /**
     * Get the current concurrency limit for a domain.
     * Useful for monitoring and debugging.
     */
    fun getCurrentLimit(domain: String): Int

    /**
     * Get statistics for a domain.
     */
    fun getStats(domain: String): DomainRateLimitStats?
}

/**
 * Statistics for rate limiting on a specific domain.
 */
data class DomainRateLimitStats(
    val currentLimit: Int,
    val successCount: Long,
    val rateLimitCount: Long,
    val lastRateLimitTime: Long?
)

/**
 * Configuration for the adaptive rate limiter.
 */
data class AdaptiveRateLimiterConfig(
    /** Initial and maximum concurrency limit per domain */
    val initialConcurrency: Int = 100,
    /** Minimum concurrency floor to prevent stalls */
    val minConcurrency: Int = 1,
    /** Factor to multiply concurrency by on rate limit (0.5 = halve) */
    val decreaseFactor: Double = 0.5,
    /** Amount to add to concurrency on successful requests */
    val increaseStep: Int = 1,
    /** Number of successful requests before increasing concurrency */
    val successesBeforeIncrease: Int = 10,
    /** Maximum number of retries for a single request */
    val maxRetries: Int = 3,
    /** Base delay for exponential backoff (milliseconds) */
    val baseBackoffMs: Long = 1000,
    /** Maximum backoff delay (milliseconds) */
    val maxBackoffMs: Long = 8000
)

@OptIn(ExperimentalTime::class)
class AdaptiveRateLimiter(
    private val config: AdaptiveRateLimiterConfig = AdaptiveRateLimiterConfig()
) : IAdaptiveRateLimiter {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val domainStates = ConcurrentHashMap<String, DomainRateLimitState>()

    override suspend fun <T> withRateLimit(url: String, block: suspend () -> T): T {
        val domain = extractDomain(url)
        val state = domainStates.computeIfAbsent(domain) {
            DomainRateLimitState(config)
        }

        var lastException: HttpClientErrorException? = null

        repeat(config.maxRetries) { attempt ->
            // Wait for a permit
            state.acquirePermit()
            try {
                val result = block()
                state.onSuccess()
                return result
            } catch (e: HttpClientErrorException) {
                if (e.statusCode == 429) {
                    lastException = e
                    logger.debug(
                        "Rate limited (429) for domain {} on attempt {}/{}, current limit: {}",
                        domain, attempt + 1, config.maxRetries, state.getCurrentLimit()
                    )
                    state.onRateLimited()

                    // Calculate exponential backoff delay
                    val backoffMs = calculateBackoff(attempt)
                    logger.debug("Backing off for {}ms before retry", backoffMs)
                    delay(backoffMs)
                    // Loop continues to retry
                } else {
                    // Non-429 errors are not retried
                    throw e
                }
            } finally {
                state.releasePermit()
            }
        }

        // All retries exhausted
        logger.warn(
            "Rate limit retries exhausted for domain {} after {} attempts",
            domain, config.maxRetries
        )
        throw RateLimitExceededException(
            url = url,
            retriesAttempted = config.maxRetries,
            cause = lastException
        )
    }

    override fun getCurrentLimit(domain: String): Int {
        return domainStates[domain]?.getCurrentLimit() ?: config.initialConcurrency
    }

    override fun getStats(domain: String): DomainRateLimitStats? {
        return domainStates[domain]?.getStats()
    }

    private fun calculateBackoff(attempt: Int): Long {
        // Exponential backoff: baseBackoff * 2^attempt, capped at maxBackoff
        val delay = config.baseBackoffMs * (1 shl attempt)
        return delay.coerceAtMost(config.maxBackoffMs)
    }

    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Tracks rate limit state for a single domain.
     * Thread-safe for concurrent access using atomic operations.
     * 
     * Uses a counting approach instead of dynamic semaphore resizing:
     * - A semaphore with max capacity (initialConcurrency) handles permit waiting
     * - An atomic counter tracks the current logical limit
     * - Requests check both the semaphore AND the current limit
     */
    private inner class DomainRateLimitState(private val config: AdaptiveRateLimiterConfig) {
        private val mutex = Mutex()
        
        // The actual semaphore with fixed max capacity
        private val semaphore = Semaphore(config.initialConcurrency)
        
        // Current logical limit (can be reduced/increased within bounds)
        private val currentLimitAtomic = AtomicInteger(config.initialConcurrency)
        
        // Count of currently active requests (those that have acquired permits)
        private val activeRequests = AtomicInteger(0)

        // Statistics
        private val successCountAtomic = AtomicLong(0)
        private var successesSinceLastIncrease = AtomicInteger(0)
        private val rateLimitCountAtomic = AtomicLong(0)
        @Volatile
        private var lastRateLimitTime: Long? = null

        /**
         * Acquire a permit, respecting both the semaphore and the current logical limit.
         * This may wait if we're at the current limit even if the semaphore has permits.
         */
        suspend fun acquirePermit() {
            while (true) {
                // First acquire from the semaphore (this is the max possible concurrency)
                semaphore.acquire()
                
                // Then check if we're within the current logical limit
                val active = activeRequests.incrementAndGet()
                val limit = currentLimitAtomic.get()
                
                if (active <= limit) {
                    // We're within the limit, proceed
                    return
                } else {
                    // We're over the limit, release and retry
                    activeRequests.decrementAndGet()
                    semaphore.release()
                    // Small delay before retry to avoid busy-waiting
                    delay(10)
                }
            }
        }

        fun releasePermit() {
            activeRequests.decrementAndGet()
            semaphore.release()
        }

        fun getCurrentLimit(): Int = currentLimitAtomic.get()

        fun getStats(): DomainRateLimitStats {
            return DomainRateLimitStats(
                currentLimit = currentLimitAtomic.get(),
                successCount = successCountAtomic.get(),
                rateLimitCount = rateLimitCountAtomic.get(),
                lastRateLimitTime = lastRateLimitTime
            )
        }

        suspend fun onSuccess() {
            successCountAtomic.incrementAndGet()
            val successes = successesSinceLastIncrease.incrementAndGet()
            
            // Only try to increase if we're below the initial limit
            val currentLimit = currentLimitAtomic.get()
            if (currentLimit < config.initialConcurrency && successes >= config.successesBeforeIncrease) {
                mutex.withLock {
                    // Double-check within lock
                    val limit = currentLimitAtomic.get()
                    if (limit < config.initialConcurrency && 
                        successesSinceLastIncrease.get() >= config.successesBeforeIncrease) {
                        
                        val newLimit = (limit + config.increaseStep)
                            .coerceAtMost(config.initialConcurrency)
                        
                        if (newLimit > limit) {
                            currentLimitAtomic.set(newLimit)
                            successesSinceLastIncrease.set(0)
                            
                            logger.debug(
                                "Increased concurrency limit to {} after {} successes",
                                newLimit, config.successesBeforeIncrease
                            )
                        }
                    }
                }
            }
        }

        suspend fun onRateLimited() {
            rateLimitCountAtomic.incrementAndGet()
            lastRateLimitTime = Clock.System.now().toEpochMilliseconds()
            successesSinceLastIncrease.set(0)

            mutex.withLock {
                val currentLimit = currentLimitAtomic.get()
                val newLimit = (currentLimit * config.decreaseFactor).toInt()
                    .coerceAtLeast(config.minConcurrency)

                if (newLimit < currentLimit) {
                    currentLimitAtomic.set(newLimit)
                    
                    logger.info(
                        "Reduced concurrency limit from {} to {} due to rate limiting",
                        currentLimit, newLimit
                    )
                }
            }
        }
    }
}

