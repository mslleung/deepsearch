package io.deepsearch.domain.browser

import io.deepsearch.domain.browser.playwright.PlaywrightBrowserRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

private const val MAX_BROWSERS: Int = 10
private const val MAX_CONTEXTS_PER_BROWSER: Int = 5
private const val STANDBY_BROWSERS: Int = 5
private val MAX_USAGE_DURATION = 1.hours

/**
 * Pool for acquiring isolated browser contexts with shared browser processes.
 *
 * This pool manages browser instances (Chrome processes) and allows multiple concurrent
 * contexts to share a single browser, reducing memory usage significantly.
 *
 * Architecture:
 * - Pool maintains up to [MAX_BROWSERS] browser instances (each ~330MB)
 * - Each browser can serve up to [MAX_CONTEXTS_PER_BROWSER] concurrent contexts
 * - Total concurrency: MAX_BROWSERS × MAX_CONTEXTS_PER_BROWSER = 50 requests
 * - Memory: MAX_BROWSERS × 330MB = ~3.3GB (vs 16.5GB with 1:1 model)
 *
 * Threading:
 * - Each browser has an apiMutex that serializes Playwright API calls
 * - Multiple contexts on the same browser share this mutex
 * - Real parallelism exists for I/O-bound operations (network, page rendering)
 */
interface IBrowserPool {
    /**
     * Acquire a browser context, execute the block, and automatically close the context.
     * The context provides full session isolation (cookies, storage, cache).
     */
    suspend fun <T> withContext(block: suspend (IBrowserContext) -> T): T
}

/**
 * Implementation of [IBrowserPool] using Playwright.
 *
 * Behavior:
 * - When acquiring: finds a browser with available capacity, creates a context
 * - If all browsers are at capacity: creates a new browser (up to MAX_BROWSERS)
 * - If pool is full and all at capacity: waits for a context to be released
 * - Browsers are recycled when expired AND have no active contexts
 */
class BrowserPool : IBrowserPool {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class PooledBrowser(
        val id: Long,
        val runtime: IBrowserRuntime,
        val browser: IBrowser,
        val createdAtMillis: Long,
        val activeContextCount: AtomicInteger = AtomicInteger(0)
    ) {
        fun isExpired(): Boolean {
            val lifetimeMs = System.currentTimeMillis() - createdAtMillis
            return lifetimeMs >= MAX_USAGE_DURATION.inWholeMilliseconds
        }

        fun hasCapacity(): Boolean = activeContextCount.get() < MAX_CONTEXTS_PER_BROWSER

        fun isIdle(): Boolean = activeContextCount.get() == 0
    }

    // Protects all mutable state below
    private val mutex = Mutex()

    // All browsers currently part of the pool
    private val allBrowsers = mutableListOf<PooledBrowser>()

    // FIFO waiters to be notified when a context slot becomes available
    private val waiters = ArrayDeque<CompletableDeferred<PooledBrowser>>()

    private val idGenerator = AtomicLong(1)

    init {
        // Prewarm standby browsers at startup
        runBlocking {
            prewarmBrowsers()
        }
    }

    /**
     * Prewarm browsers up to [STANDBY_BROWSERS] count.
     * Called at startup to reduce latency for initial requests.
     */
    private suspend fun prewarmBrowsers() {
        logger.info("Prewarming {} standby browsers...", STANDBY_BROWSERS)
        mutex.withLock {
            while (allBrowsers.size < STANDBY_BROWSERS) {
                val browser = createNewBrowserLocked()
                logger.info(
                    "Pre-warmed browser #{} (pool size: {}/{})",
                    browser.id,
                    allBrowsers.size,
                    MAX_BROWSERS
                )
            }
        }
        logger.info("Prewarming complete: {} browsers ready", allBrowsers.size)
    }

    override suspend fun <T> withContext(block: suspend (IBrowserContext) -> T): T {
        val pooledBrowser = acquireBrowserSlot()
        val context = pooledBrowser.browser.createContext()
        return try {
            block(context)
        } finally {
            try {
                context.close()
            } catch (t: Throwable) {
                logger.warn("Error closing context on browser #{}: {}", pooledBrowser.id, t.message)
            }
            releaseContextSlot(pooledBrowser)
        }
    }

    /**
     * Acquire a context slot from a browser with capacity.
     * If no browser has capacity, wait until one becomes available or create a new one.
     */
    private suspend fun acquireBrowserSlot(): PooledBrowser {
        val result: Any = mutex.withLock {
            // First, try to recycle expired browsers that are idle
            recycleExpiredIdleBrowsersLocked()

            // 1) Find a browser with available capacity
            val availableBrowser = allBrowsers.find { it.hasCapacity() && !it.isExpired() }
            if (availableBrowser != null) {
                availableBrowser.activeContextCount.incrementAndGet()
                logger.debug(
                    "Acquired context slot on browser #{} (active contexts: {}/{})",
                    availableBrowser.id,
                    availableBrowser.activeContextCount.get(),
                    MAX_CONTEXTS_PER_BROWSER
                )
                return@withLock availableBrowser
            }

            // 2) No browser has capacity - create a new one if under limit
            if (allBrowsers.size < MAX_BROWSERS) {
                val created = createNewBrowserLocked()
                created.activeContextCount.incrementAndGet()
                logger.info(
                    "Created new browser #{} (pool size: {}/{}, active contexts: 1/{})",
                    created.id,
                    allBrowsers.size,
                    MAX_BROWSERS,
                    MAX_CONTEXTS_PER_BROWSER
                )
                return@withLock created
            }

            // 3) Pool is at capacity and all browsers are full. Wait for a slot.
            val waiter = CompletableDeferred<PooledBrowser>()
            waiters.addLast(waiter)
            logger.info(
                "All {} browsers at capacity ({} contexts each); waiting for release (waiters={})",
                allBrowsers.size,
                MAX_CONTEXTS_PER_BROWSER,
                waiters.size
            )
            waiter
        }

        return when (result) {
            is PooledBrowser -> result
            is CompletableDeferred<*> -> {
                @Suppress("UNCHECKED_CAST")
                val browser = (result as CompletableDeferred<PooledBrowser>).await()
                browser.activeContextCount.incrementAndGet()
                browser
            }
            else -> error("Unexpected type from critical section: ${result::class}")
        }
    }

    /**
     * Release a context slot back to the pool.
     * If the browser is expired and now idle, recycle it.
     * If there are waiters, notify the next one.
     */
    private suspend fun releaseContextSlot(pooledBrowser: PooledBrowser) {
        var toRecycle: PooledBrowser? = null

        mutex.withLock {
            val newCount = pooledBrowser.activeContextCount.decrementAndGet()
            logger.debug(
                "Released context slot on browser #{} (active contexts: {}/{})",
                pooledBrowser.id,
                newCount,
                MAX_CONTEXTS_PER_BROWSER
            )

            // Check if browser should be recycled
            if (pooledBrowser.isExpired() && pooledBrowser.isIdle()) {
                logger.info("Browser #{} is expired and idle, scheduling for recycling", pooledBrowser.id)
                allBrowsers.remove(pooledBrowser)
                toRecycle = pooledBrowser
            }

            // Notify a waiter if there's capacity now
            if (pooledBrowser.hasCapacity() && !pooledBrowser.isExpired()) {
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    waiter.complete(pooledBrowser)
                    logger.debug("Notified waiter about available slot on browser #{}", pooledBrowser.id)
                }
            } else if (waiters.isNotEmpty() && allBrowsers.size < MAX_BROWSERS) {
                // Browser is expired or still full, but we can create a new one for the waiter
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    val created = createNewBrowserLocked()
                    waiter.complete(created)
                    logger.info(
                        "Created new browser #{} for waiter (pool size: {}/{})",
                        created.id,
                        allBrowsers.size,
                        MAX_BROWSERS
                    )
                }
            }
        }

        // Close outside the lock to avoid blocking
        toRecycle?.let { browser ->
            try {
                browser.browser.close()
                browser.runtime.close()
                logger.info("Recycled browser #{}", browser.id)
            } catch (t: Throwable) {
                logger.warn("Error while recycling browser #{}: {}", browser.id, t.message)
            }
        }
    }

    private suspend fun createNewBrowserLocked(): PooledBrowser {
        val id = idGenerator.getAndIncrement()
        logger.info("Creating new browser #{}", id)
        val runtime = PlaywrightBrowserRuntime()
        val browser = runtime.createBrowser()
        val pooled = PooledBrowser(
            id = id,
            runtime = runtime,
            browser = browser,
            createdAtMillis = System.currentTimeMillis()
        )
        allBrowsers.add(pooled)
        return pooled
    }

    private suspend fun recycleExpiredIdleBrowsersLocked() {
        val expired = allBrowsers.filter { it.isExpired() && it.isIdle() }
        for (browser in expired) {
            allBrowsers.remove(browser)
            try {
                browser.runtime.close()
                logger.info("Recycled expired idle browser #{}", browser.id)
            } catch (t: Throwable) {
                logger.warn("Error while recycling browser #{}: {}", browser.id, t.message)
            }
        }
    }
}

