package io.deepsearch.domain.browser

import io.deepsearch.domain.browser.playwright.PlaywrightBrowserRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

private const val maxPoolSize: Int = 1000
private val maxUsageDuration = 1.hours
private const val standbyMinIdleRuntimes: Int = 5

interface IBrowserRuntimePool {
    suspend fun acquireRuntime(block: suspend (IBrowserRuntime) -> Unit)
}

/**
 * Provide lifecycle management of browser runtime instances.
 *
 * Browser runtimes (e.g. Playwright Chromium instances) are expensive to create.
 * This pool coordinates reuse, creation, and recycling of runtimes across concurrent tasks.
 *
 * Behavior:
 * - Acquire returns an idle runtime when available.
 * - If none are idle and total < maxPoolSize, a new runtime is created and added to the pool.
 * - If the pool is full and all runtimes are in use, callers are suspended until a runtime is released.
 * - Eviction: a runtime is considered expired when its lifetime exceeds [maxUsageDuration]. Expired idle
 *   runtimes are recycled immediately on the next acquire call. If a runtime becomes expired while in use,
 *   it will be closed and removed from the pool upon release (and never handed out again).
 */
class BrowserRuntimePool : IBrowserRuntimePool {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class PooledRuntime(
        val id: Long,
        val runtime: IBrowserRuntime,
        val createdAtMillis: Long,
        var inUse: Boolean
    ) {
        fun isExpired(): Boolean {
            val lifetimeMs = System.currentTimeMillis() - createdAtMillis
            return lifetimeMs >= maxUsageDuration.inWholeMilliseconds
        }
    }

    // Protects all mutable state below
    private val mutex = Mutex()

    // All runtimes currently part of the pool (active or idle)
    private val allRuntimes = mutableListOf<PooledRuntime>()

    // Idle queue (FIFO) of runtimes available for immediate reuse
    private val idleQueue = ArrayDeque<PooledRuntime>()

    // FIFO waiters to be notified when a runtime becomes available
    private val waiters = ArrayDeque<CompletableDeferred<PooledRuntime>>()

    private val idGenerator = AtomicLong(1)

    init {
        ensureStandbyIdleLocked()
    }

    private suspend fun acquireRuntime(): PooledRuntime {
        val pooled: PooledRuntime = mutex.withLock {
            // Recycle any idle runtimes that have exceeded max usage duration
            recycleExpiredIdleLocked()

            // 1) Serve from idle if available
            val existing = idleQueue.removeFirstOrNull()
            if (existing != null) {
                existing.inUse = true
                logger.debug("Acquired runtime #{} from idle queue", existing.id)
                // Top-up standby idle runtimes if needed
                ensureStandbyIdleLocked()
                return@withLock existing
            }

            // 2) Create a new one if under capacity
            if (allRuntimes.size < maxPoolSize) {
                val created = createNewRuntimeLocked()
                created.inUse = true
                logger.info("Created new runtime #{} (pool size after create: {})", created.id, allRuntimes.size)
                // After provisioning for the caller, pre-warm idle runtimes up to standby threshold
                ensureStandbyIdleLocked()
                return@withLock created
            }

            // 3) Pool is at capacity and all are in use. Prepare to wait.
            val waiter = CompletableDeferred<PooledRuntime>()
            waiters.addLast(waiter)
            logger.info("All {} runtimes in use; waiting for a release (waiters={})", allRuntimes.size, waiters.size)
            waiter
        }.let { deferredOrPooled ->
            // If we got a PooledRuntime directly, return it; otherwise await the deferred
            when (deferredOrPooled) {
                is PooledRuntime -> deferredOrPooled
                is CompletableDeferred<*> -> @Suppress("UNCHECKED_CAST") (deferredOrPooled as CompletableDeferred<PooledRuntime>).await()
                else -> error("Unexpected type from critical section")
            }
        }

        // Wrap with a handle that returns the runtime to the pool when closed by the caller
        return pooled
    }

    override suspend fun acquireRuntime(block: suspend (IBrowserRuntime) -> Unit) {
        val pooledRuntime = acquireRuntime()
        block(pooledRuntime.runtime)
        release(pooledRuntime)
    }

    /**
     * Called by the pooled handle when user calls close(). Returns the runtime to the pool or recycles it.
     */
    private suspend fun release(pooled: PooledRuntime) {
        var toClose: PooledRuntime? = null

        mutex.withLock {
            if (!pooled.inUse) {
                // ignore duplicate closes
                logger.warn("Release called on runtime #{} which is not in use.", pooled.id)
                return
            }

            if (pooled.isExpired()) {
                // Remove from pool and schedule close outside the lock
                logger.info("Recycling runtime #{} upon release.", pooled.id)
                pooled.inUse = false
                removeLocked(pooled)
                toClose = pooled

                // If there are waiters, we may need to either hand them an idle runtime or create a new one
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    // Try to serve from idle after removal
                    recycleExpiredIdleLocked()
                    val idle = idleQueue.removeFirstOrNull()
                    if (idle != null) {
                        idle.inUse = true
                        waiter.complete(idle)
                        logger.debug("Served waiter with idle runtime #{} after recycling #{}.", idle.id, pooled.id)
                    } else {
                        // If under capacity after removal, create a new one immediately for the waiter
                        if (allRuntimes.size < maxPoolSize) {
                            val created = createNewRuntimeLocked().also { it.inUse = true }
                            waiter.complete(created)
                            logger.info(
                                "Created runtime #{} to serve waiter after recycling #{} (pool size: {}).",
                                created.id,
                                pooled.id,
                                allRuntimes.size
                            )
                        } else {
                            // Should not happen because we removed one above
                            throw Exception("Runtime pool is full even after we released a runtime")
                        }
                    }
                }
            } else {
                // Not recycled; either pass directly to a waiter or put back to idle
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    pooled.inUse = true // remains in use by the next waiter
                    waiter.complete(pooled)
                    logger.debug("Handed off runtime #{} directly to next waiter.", pooled.id)
                } else {
                    pooled.inUse = false
                    idleQueue.addLast(pooled)
                    logger.debug("Returned runtime #{} to idle queue (idle count={}).", pooled.id, idleQueue.size)
                }
            }

            // After any state change, try to maintain standby idle runtimes
            ensureStandbyIdleLocked()
        }

        // Close outside the lock to avoid blocking other threads while Playwright tears down
        val closeTargetLocal = toClose
        if (closeTargetLocal != null) {
            try {
                closeTargetLocal.runtime.close()
            } catch (t: Throwable) {
                logger.warn("Error while closing runtime #{}: {}", closeTargetLocal.id, t.message)
            }
        }
    }

    private fun createNewRuntimeLocked(): PooledRuntime {
        val id = idGenerator.getAndIncrement()
        logger.info("Creating new runtime #{}", id)
        val runtime = PlaywrightBrowserRuntime()
        val pooled = PooledRuntime(
            id = id,
            runtime = runtime,
            createdAtMillis = System.currentTimeMillis(),
            inUse = false
        )
        allRuntimes.add(pooled)
        return pooled
    }

    private suspend fun recycleExpiredIdleLocked() {
        if (idleQueue.isEmpty()) return
        val iterator = idleQueue.iterator()
        while (iterator.hasNext()) {
            val r = iterator.next()
            if (r.isExpired()) {
                iterator.remove()
                removeAndCloseLocked(r)
                logger.info("Recycled idle expired runtime #{}.", r.id)
            }
        }
    }

    /**
     * Ensure we have at least [standbyMinIdleRuntimes] idle runtimes ready for immediate use,
     * without exceeding [maxPoolSize]. Must be called with [mutex] held.
     */
    private fun ensureStandbyIdleLocked() {
        while (idleQueue.size < standbyMinIdleRuntimes && allRuntimes.size < maxPoolSize) {
            val prewarmed = createNewRuntimeLocked()
            idleQueue.addLast(prewarmed)
            logger.info(
                "Pre-warmed standby runtime #{} (idle={}; pool={}/{})",
                prewarmed.id,
                idleQueue.size,
                allRuntimes.size,
                maxPoolSize
            )
        }
    }

    private fun removeLocked(p: PooledRuntime) {
        idleQueue.remove(p)
        allRuntimes.remove(p)
    }

    private suspend fun removeAndCloseLocked(p: PooledRuntime) {
        removeLocked(p)
        try {
            p.runtime.close()
        } catch (t: Throwable) {
            logger.warn("Error while closing runtime #{}: {}", p.id, t.message)
        }
    }
}

