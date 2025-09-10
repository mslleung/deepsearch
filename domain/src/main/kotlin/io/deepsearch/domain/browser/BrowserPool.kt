package io.deepsearch.domain.browser

import io.deepsearch.domain.browser.playwright.PlaywrightBrowser
import io.deepsearch.domain.agents.ITableIdentificationAgent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

private const val maxPoolSize: Int = 8
private val maxUsageDuration: Duration = Duration.ofHours(1)
private const val standbyMinIdleBrowsers: Int = 0

interface IBrowserPool {
    suspend fun acquireBrowser(): IBrowser
}

/**
 * Provide lifecycle management of browser automation instances.
 *
 * Browser runtimes (e.g. Chrome, Firefox etc.) are expensive. We may potentially run many of them.
 * This pool coordinates reuse, creation, and recycling of browsers across concurrent tasks.
 *
 * Behavior:
 * - Acquire returns an idle browser when available.
 * - If none are idle and total < maxPoolSize, a new browser is created and added to the pool.
 * - If the pool is full and all browsers are in use, callers are suspended until a browser is released.
 * - Eviction: a browser is considered expired when its lifetime exceeds [maxUsageDuration]. Expired idle
 *   browsers are recycled immediately on the next acquire call. If a browser becomes expired while in use,
 *   it will be closed and removed from the pool upon release (and never handed out again).
 */
class BrowserPool(
    private val tableIdentificationAgent: ITableIdentificationAgent
) : IBrowserPool {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class PooledBrowser(
        val id: Long,
        val browser: IBrowser,
        val createdAtMillis: Long,
        var inUse: Boolean
    ) {
        fun isExpired(): Boolean {
            val lifetimeMs = System.currentTimeMillis() - createdAtMillis
            return lifetimeMs >= maxUsageDuration.toMillis()
        }
    }

    // Protects all mutable state below
    private val mutex = Mutex()

    // All browsers currently part of the pool (active or idle)
    private val allBrowsers = mutableListOf<PooledBrowser>()

    // Idle queue (FIFO) of browsers available for immediate reuse
    private val idleQueue = ArrayDeque<PooledBrowser>()

    // FIFO waiters to be notified when a browser becomes available
    private val waiters = ArrayDeque<CompletableDeferred<PooledBrowser>>()

    private val idGenerator = AtomicLong(1)

    override suspend fun acquireBrowser(): IBrowser {
        val pooled: PooledBrowser = mutex.withLock {
            // Recycle any idle browsers that have exceeded max usage duration
            recycleExpiredIdleLocked()

            // 1) Serve from idle if available
            val existing = idleQueue.removeFirstOrNull()
            if (existing != null) {
                existing.inUse = true
                logger.debug("Acquired browser #{} from idle queue", existing.id)
                // Top-up standby idle browsers if needed
                ensureStandbyIdleLocked()
                return@withLock existing
            }

            // 2) Create a new one if under capacity
            if (allBrowsers.size < maxPoolSize) {
                val created = createNewBrowserLocked()
                created.inUse = true
                logger.info("Created new browser #{} (pool size after create: {})", created.id, allBrowsers.size)
                // After provisioning for the caller, pre-warm idle browsers up to standby threshold
                ensureStandbyIdleLocked()
                return@withLock created
            }

            // 3) Pool is at capacity and all are in use. Prepare to wait.
            val waiter = CompletableDeferred<PooledBrowser>()
            waiters.addLast(waiter)
            logger.info("All {} browsers in use; waiting for a release (waiters={})", allBrowsers.size, waiters.size)
            waiter
        }.let { deferredOrPooled ->
            // If we got a PooledBrowser directly, return it; otherwise await the deferred
            when (deferredOrPooled) {
                is PooledBrowser -> deferredOrPooled
                is CompletableDeferred<*> -> @Suppress("UNCHECKED_CAST") (deferredOrPooled as CompletableDeferred<PooledBrowser>).await()
                else -> error("Unexpected type from critical section")
            }
        }

        // Wrap with a handle that returns the browser to the pool when closed by the caller
        return PooledBrowserHandle(this, pooled)
    }

    /**
     * Called by the pooled handle when user calls close(). Returns the browser to the pool or recycles it.
     */
    private suspend fun release(pooled: PooledBrowser) {
        var toClose: PooledBrowser? = null

        mutex.withLock {
            if (!pooled.inUse) {
                // ignore duplicate closes
                logger.warn("Release called on browser #{} which is not in use.", pooled.id)
                return
            }

            if (pooled.isExpired()) {
                // Remove from pool and schedule close outside the lock
                logger.info("Recycling browser #{} upon release.", pooled.id)
                pooled.inUse = false
                removeLocked(pooled)
                toClose = pooled

                // If there are waiters, we may need to either hand them an idle browser or create a new one
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    // Try to serve from idle after removal
                    recycleExpiredIdleLocked()
                    val idle = idleQueue.removeFirstOrNull()
                    if (idle != null) {
                        idle.inUse = true
                        waiter.complete(idle)
                        logger.debug("Served waiter with idle browser #{} after recycling #{}.", idle.id, pooled.id)
                    } else {
                        // If under capacity after removal, create a new one immediately for the waiter
                        if (allBrowsers.size < maxPoolSize) {
                            val created = createNewBrowserLocked().also { it.inUse = true }
                            waiter.complete(created)
                            logger.info(
                                "Created browser #{} to serve waiter after recycling #{} (pool size: {}).",
                                created.id,
                                pooled.id,
                                allBrowsers.size
                            )
                        } else {
                            // Should not happen because we removed one above
                            throw Exception("Browser pool is full even after we released a browser")
                        }
                    }
                }
            } else {
                // Not recycled; either pass directly to a waiter or put back to idle
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    pooled.inUse = true // remains in use by the next waiter
                    waiter.complete(pooled)
                    logger.debug("Handed off browser #{} directly to next waiter.", pooled.id)
                } else {
                    pooled.inUse = false
                    idleQueue.addLast(pooled)
                    logger.debug("Returned browser #{} to idle queue (idle count={}).", pooled.id, idleQueue.size)
                }
            }

            // After any state change, try to maintain standby idle browsers
            ensureStandbyIdleLocked()
        }

        // Close outside the lock to avoid blocking other threads while Playwright tears down
        val closeTargetLocal = toClose
        if (closeTargetLocal != null) {
            try {
                closeTargetLocal.browser.close()
            } catch (t: Throwable) {
                logger.warn("Error while closing browser #{}: {}", closeTargetLocal.id, t.message)
            }
        }
    }

    private fun createNewBrowserLocked(): PooledBrowser {
        val id = idGenerator.getAndIncrement()
        logger.info("Creating new browser #{}", id)
        val browser = PlaywrightBrowser(tableIdentificationAgent)
        val pooled = PooledBrowser(
            id = id,
            browser = browser,
            createdAtMillis = System.currentTimeMillis(),
            inUse = false
        )
        allBrowsers.add(pooled)
        return pooled
    }

    private suspend fun recycleExpiredIdleLocked() {
        if (idleQueue.isEmpty()) return
        val iterator = idleQueue.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            if (b.isExpired()) {
                iterator.remove()
                removeAndCloseLocked(b)
                logger.info("Recycled idle expired browser #{}.", b.id)
            }
        }
    }

    /**
     * Ensure we have at least [standbyMinIdleBrowsers] idle browsers ready for immediate use,
     * without exceeding [maxPoolSize]. Must be called with [mutex] held.
     */
    private fun ensureStandbyIdleLocked() {
        if (standbyMinIdleBrowsers <= 0) return
        while (idleQueue.size < standbyMinIdleBrowsers && allBrowsers.size < maxPoolSize) {
            val prewarmed = createNewBrowserLocked()
            idleQueue.addLast(prewarmed)
            logger.info(
                "Pre-warmed standby browser #{} (idle={}; pool={}/{})",
                prewarmed.id,
                idleQueue.size,
                allBrowsers.size,
                maxPoolSize
            )
        }
    }

    private fun removeLocked(p: PooledBrowser) {
        idleQueue.remove(p)
        allBrowsers.remove(p)
    }

    private suspend fun removeAndCloseLocked(p: PooledBrowser) {
        removeLocked(p)
        try {
            p.browser.close()
        } catch (t: Throwable) {
            logger.warn("Error while closing browser #{}: {}", p.id, t.message)
        }
    }

    /**
     * Pooled handle that delegates to the underlying browser and returns to the pool on close().
     * This ensures existing call sites using try/finally { browser.close() } automatically participate
     * in pooling without code changes.
     */
    private class PooledBrowserHandle(
        private val pool: BrowserPool,
        private val pooled: PooledBrowser
    ) : IBrowser {
        override fun createContext(): IBrowserContext = pooled.browser.createContext()

        override suspend fun close() {
            pool.release(pooled)
        }
    }
}