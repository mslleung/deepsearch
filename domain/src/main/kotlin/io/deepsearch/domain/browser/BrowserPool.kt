package io.deepsearch.domain.browser

import io.deepsearch.domain.browser.playwright.PlaywrightBrowserRuntime
import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

private const val MAX_BROWSERS: Int = 10
private const val MAX_PAGES_PER_BROWSER: Int = 15
private const val STANDBY_BROWSERS: Int = 1
private val MAX_USAGE_DURATION = 1.hours

/**
 * Pool for acquiring browser pages with shared browser processes and contexts.
 *
 * This pool manages browser instances (Chrome processes) with a single shared context per browser,
 * allowing multiple concurrent pages to share rendering resources, reducing memory usage significantly.
 *
 * Architecture:
 * - Pool maintains up to [MAX_BROWSERS] browser instances (each ~330MB)
 * - Each browser has exactly ONE context (shared rendering/compositor)
 * - Each context can serve up to [MAX_PAGES_PER_BROWSER] concurrent pages
 * - Total concurrency: MAX_BROWSERS × MAX_PAGES_PER_BROWSER = 150 requests
 * - Memory: Significantly reduced vs per-request contexts (contexts have rendering overhead)
 *
 * Threading:
 * - Each browser has an apiMutex that serializes Playwright API calls
 * - Multiple pages on the same browser share this mutex
 * - Real parallelism exists for I/O-bound operations (network, page rendering)
 *
 * Note: Pages within a browser share cookies, localStorage, and cache since they share a context.
 */
interface IBrowserPool {
    /**
     * Acquire a browser page, execute the block, and automatically close the page.
     * Pages within the same browser share session state (cookies, storage, cache).
     */
    suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T
}

/**
 * Implementation of [IBrowserPool] using Playwright.
 *
 * Behavior:
 * - When acquiring: finds a browser with available page capacity, creates a page
 * - If all browsers are at capacity: creates a new browser (up to MAX_BROWSERS)
 * - If pool is full and all at capacity: waits for a page to be released
 * - Browsers are recycled when expired AND have no active pages
 */
class BrowserPool(
    private val applicationCoroutineScope: IApplicationCoroutineScope
) : IBrowserPool {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class PooledBrowser(
        val id: Long,
        val runtime: IBrowserRuntime,
        val browser: IBrowser,
        val context: IBrowserContext,
        val createdAtMillis: Long,
        val activePageCount: AtomicInteger = AtomicInteger(0)
    ) {
        fun isExpired(): Boolean {
            val lifetimeMs = System.currentTimeMillis() - createdAtMillis
            return lifetimeMs >= MAX_USAGE_DURATION.inWholeMilliseconds
        }

        fun hasCapacity(): Boolean = activePageCount.get() < MAX_PAGES_PER_BROWSER

        fun isIdle(): Boolean = activePageCount.get() == 0
    }

    // Protects all mutable state below
    private val mutex = Mutex()

    // All browsers currently part of the pool
    private val allBrowsers = mutableListOf<PooledBrowser>()

    // FIFO waiters to be notified when a page slot becomes available
    private val waiters = ArrayDeque<CompletableDeferred<PooledBrowser>>()

    private val idGenerator = AtomicLong(1)

    init {
        // Prewarm standby browsers at startup (non-blocking)
        applicationCoroutineScope.scope.launch {
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

    override suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T {
        val pooledBrowser = acquirePageSlot()
        val page = pooledBrowser.context.newPage()
        return try {
            block(page)
        } finally {
            try {
                page.close()
            } catch (t: Throwable) {
                logger.warn("Error closing page on browser #{}: {}", pooledBrowser.id, t.message)
            }
            releasePageSlot(pooledBrowser)
        }
    }

    /**
     * Acquire a page slot from a browser with capacity.
     * If no browser has capacity, wait until one becomes available or create a new one.
     */
    private suspend fun acquirePageSlot(): PooledBrowser {
        val result: Any = mutex.withLock {
            // First, try to recycle expired browsers that are idle
            recycleExpiredIdleBrowsersLocked()

            // 1) Find a browser with available capacity
            val availableBrowser = allBrowsers.find { it.hasCapacity() && !it.isExpired() }
            if (availableBrowser != null) {
                availableBrowser.activePageCount.incrementAndGet()
                logger.debug(
                    "Acquired page slot on browser #{} (active pages: {}/{})",
                    availableBrowser.id,
                    availableBrowser.activePageCount.get(),
                    MAX_PAGES_PER_BROWSER
                )
                return@withLock availableBrowser
            }

            // 2) No browser has capacity - create a new one if under limit
            if (allBrowsers.size < MAX_BROWSERS) {
                val created = createNewBrowserLocked()
                created.activePageCount.incrementAndGet()
                logger.info(
                    "Created new browser #{} (pool size: {}/{}, active pages: 1/{})",
                    created.id,
                    allBrowsers.size,
                    MAX_BROWSERS,
                    MAX_PAGES_PER_BROWSER
                )
                return@withLock created
            }

            // 3) Pool is at capacity and all browsers are full. Wait for a slot.
            val waiter = CompletableDeferred<PooledBrowser>()
            waiters.addLast(waiter)
            logger.info(
                "All {} browsers at capacity ({} pages each); waiting for release (waiters={})",
                allBrowsers.size,
                MAX_PAGES_PER_BROWSER,
                waiters.size
            )
            waiter
        }

        return when (result) {
            is PooledBrowser -> result
            is CompletableDeferred<*> -> {
                @Suppress("UNCHECKED_CAST")
                val browser = (result as CompletableDeferred<PooledBrowser>).await()
                browser.activePageCount.incrementAndGet()
                browser
            }
            else -> error("Unexpected type from critical section: ${result::class}")
        }
    }

    /**
     * Release a page slot back to the pool.
     * If the browser is expired and now idle, recycle it.
     * If there are waiters, notify the next one.
     */
    private suspend fun releasePageSlot(pooledBrowser: PooledBrowser) {
        var toRecycle: PooledBrowser? = null

        mutex.withLock {
            val newCount = pooledBrowser.activePageCount.decrementAndGet()
            logger.debug(
                "Released page slot on browser #{} (active pages: {}/{})",
                pooledBrowser.id,
                newCount,
                MAX_PAGES_PER_BROWSER
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
                browser.context.close()
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
        val context = browser.createContext()
        val pooled = PooledBrowser(
            id = id,
            runtime = runtime,
            browser = browser,
            context = context,
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
                browser.context.close()
                browser.browser.close()
                browser.runtime.close()
                logger.info("Recycled expired idle browser #{}", browser.id)
            } catch (t: Throwable) {
                logger.warn("Error while recycling browser #{}: {}", browser.id, t.message)
            }
        }
    }
}
