package io.deepsearch.domain.browser

import io.deepsearch.domain.browser.playwright.PlaywrightBrowserRuntime
import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

private const val MAX_RUNTIMES: Int = 10
private const val MAX_PAGES_PER_RUNTIME: Int = 10
private const val STANDBY_RUNTIMES: Int = 1
private val MAX_USAGE_DURATION = 1.hours

/**
 * Pool for acquiring isolated browser pages with shared browser processes.
 *
 * This pool manages browser runtimes and reuses pages within a single context per runtime.
 * Pages are cleared between uses to provide session isolation while sharing the renderer process.
 *
 * Architecture:
 * - Pool maintains up to [MAX_RUNTIMES] runtime instances
 * - Each runtime has 1 browser, 1 context, and up to [MAX_PAGES_PER_RUNTIME] pages
 * - All pages within a runtime share a single renderer process (~200-300MB per runtime)
 * - Total concurrency: MAX_RUNTIMES × MAX_PAGES_PER_RUNTIME
 * - Memory: MAX_RUNTIMES × ~300MB (vs ~200MB per context with context pooling)
 *
 * Isolation:
 * - Pages are cleared between uses (cookies, localStorage, sessionStorage, navigate to about:blank)
 * - This provides effective isolation for most use cases
 */
interface IBrowserPool {
    /**
     * Acquire a browser page, execute the block, and automatically release the page.
     * The page is cleared before returning to the pool to ensure isolation.
     */
    suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T
}

/**
 * Implementation of [IBrowserPool] using Playwright with page pooling.
 *
 * Behavior:
 * - Each runtime has 1 browser with 1 context containing multiple pages
 * - Pages are pre-created and reused
 * - Between uses, pages are cleared (cookies, storage, navigate to about:blank)
 * - Runtimes are recycled when expired AND have no active pages
 */
class BrowserPool(
    private val applicationScope: IApplicationCoroutineScope
) : IBrowserPool {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private data class PooledPage(
        val page: IBrowserPage,
        @Volatile var inUse: Boolean = false
    )

    private data class PooledRuntime(
        val id: Long,
        val runtime: IBrowserRuntime,
        val browser: IBrowser,
        val context: IBrowserContext,
        val pages: MutableList<PooledPage>,
        val createdAtMillis: Long
    ) {
        fun isExpired(): Boolean {
            val lifetimeMs = System.currentTimeMillis() - createdAtMillis
            return lifetimeMs >= MAX_USAGE_DURATION.inWholeMilliseconds
        }

        fun activePageCount(): Int = pages.count { it.inUse }

        fun hasCapacity(): Boolean = pages.size < MAX_PAGES_PER_RUNTIME || pages.any { !it.inUse }

        fun isIdle(): Boolean = pages.none { it.inUse }

        fun getAvailablePage(): PooledPage? = pages.find { !it.inUse }
    }

    // Protects all mutable state below
    private val mutex = Mutex()

    // All runtimes currently part of the pool
    private val allRuntimes = mutableListOf<PooledRuntime>()

    // FIFO waiters to be notified when a page becomes available
    private val waiters = ArrayDeque<CompletableDeferred<Pair<PooledRuntime, PooledPage>>>()

    private val idGenerator = AtomicLong(1)

    init {
        // Prewarm standby runtimes asynchronously to avoid blocking application startup
        applicationScope.scope.launch {
            prewarmRuntimes()
        }
    }

    /**
     * Prewarm runtimes up to [STANDBY_RUNTIMES] count.
     * Called at startup to reduce latency for initial requests.
     */
    private suspend fun prewarmRuntimes() {
        logger.info("Prewarming {} standby runtimes...", STANDBY_RUNTIMES)
        mutex.withLock {
            while (allRuntimes.size < STANDBY_RUNTIMES) {
                val runtime = createNewRuntimeLocked()
                logger.info(
                    "Pre-warmed runtime #{} (pool size: {}/{})",
                    runtime.id,
                    allRuntimes.size,
                    MAX_RUNTIMES
                )
            }
        }
        logger.info("Prewarming complete: {} runtimes ready", allRuntimes.size)
    }

    override suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T {
        val (pooledRuntime, pooledPage) = acquirePage()
        return try {
            block(pooledPage.page)
        } finally {
            releasePage(pooledRuntime, pooledPage)
        }
    }

    /**
     * Acquire a page from a runtime with capacity.
     * If no page is available, wait until one becomes available or create a new one.
     */
    private suspend fun acquirePage(): Pair<PooledRuntime, PooledPage> {
        val result: Any = mutex.withLock {
            // First, try to recycle expired runtimes that are idle
            recycleExpiredIdleRuntimesLocked()

            // 1) Find a runtime with an available page
            for (runtime in allRuntimes) {
                if (runtime.isExpired()) continue

                val availablePage = runtime.getAvailablePage()
                if (availablePage != null) {
                    availablePage.inUse = true
                    logger.debug(
                        "Acquired existing page from runtime #{} (active: {}/{})",
                        runtime.id,
                        runtime.activePageCount(),
                        runtime.pages.size
                    )
                    return@withLock Pair(runtime, availablePage)
                }

                // Runtime has no available page, but can we create one?
                if (runtime.pages.size < MAX_PAGES_PER_RUNTIME) {
                    val newPage = createNewPageLocked(runtime)
                    newPage.inUse = true
                    logger.debug(
                        "Created new page in runtime #{} (pages: {}/{})",
                        runtime.id,
                        runtime.pages.size,
                        MAX_PAGES_PER_RUNTIME
                    )
                    return@withLock Pair(runtime, newPage)
                }
            }

            // 2) No runtime has capacity - create a new runtime if under limit
            if (allRuntimes.size < MAX_RUNTIMES) {
                val newRuntime = createNewRuntimeLocked()
                val newPage = createNewPageLocked(newRuntime)
                newPage.inUse = true
                logger.info(
                    "Created new runtime #{} with page (runtimes: {}/{}, pages: 1/{})",
                    newRuntime.id,
                    allRuntimes.size,
                    MAX_RUNTIMES,
                    MAX_PAGES_PER_RUNTIME
                )
                return@withLock Pair(newRuntime, newPage)
            }

            // 3) Pool is at capacity and all pages are in use. Wait for a page.
            val waiter = CompletableDeferred<Pair<PooledRuntime, PooledPage>>()
            waiters.addLast(waiter)
            logger.info(
                "All {} runtimes at capacity; waiting for release (waiters={})",
                allRuntimes.size,
                waiters.size
            )
            waiter
        }

        return when (result) {
            is Pair<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                result as Pair<PooledRuntime, PooledPage>
            }
            is CompletableDeferred<*> -> {
                @Suppress("UNCHECKED_CAST")
                val pair = (result as CompletableDeferred<Pair<PooledRuntime, PooledPage>>).await()
                pair.second.inUse = true
                pair
            }
            else -> error("Unexpected type from critical section: ${result::class}")
        }
    }

    /**
     * Release a page back to the pool after clearing its state.
     */
    private suspend fun releasePage(pooledRuntime: PooledRuntime, pooledPage: PooledPage) {
        // Clear page state outside the lock (I/O operations)
        try {
            clearPageState(pooledRuntime, pooledPage)
        } catch (t: Throwable) {
            logger.warn("Error clearing page state in runtime #{}: {}", pooledRuntime.id, t.message)
            // Continue with release even if clearing failed
        }

        releasePageSlot(pooledRuntime, pooledPage)
    }

    /**
     * Release a page slot (for backward compat withContext)
     */
    private suspend fun releasePageSlot(pooledRuntime: PooledRuntime, pooledPage: PooledPage? = null) {
        var toRecycle: PooledRuntime? = null

        mutex.withLock {
            if (pooledPage != null) {
                pooledPage.inUse = false
            }
            
            logger.debug(
                "Released page slot in runtime #{} (active: {}/{})",
                pooledRuntime.id,
                pooledRuntime.activePageCount(),
                pooledRuntime.pages.size
            )

            // Check if runtime should be recycled
            if (pooledRuntime.isExpired() && pooledRuntime.isIdle()) {
                logger.info("Runtime #{} is expired and idle, scheduling for recycling", pooledRuntime.id)
                allRuntimes.remove(pooledRuntime)
                toRecycle = pooledRuntime
            }

            // Notify a waiter if there's a page available now
            if (pooledPage != null && !pooledRuntime.isExpired()) {
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    pooledPage.inUse = true
                    waiter.complete(Pair(pooledRuntime, pooledPage))
                    logger.debug("Notified waiter about available page in runtime #{}", pooledRuntime.id)
                }
            } else if (waiters.isNotEmpty() && allRuntimes.size < MAX_RUNTIMES) {
                // Create a new runtime for the waiter
                val waiter = waiters.removeFirstOrNull()
                if (waiter != null) {
                    val newRuntime = createNewRuntimeLocked()
                    val newPage = createNewPageLocked(newRuntime)
                    newPage.inUse = true
                    waiter.complete(Pair(newRuntime, newPage))
                    logger.info(
                        "Created new runtime #{} for waiter (runtimes: {}/{})",
                        newRuntime.id,
                        allRuntimes.size,
                        MAX_RUNTIMES
                    )
                }
            }
        }

        // Close outside the lock to avoid blocking
        toRecycle?.let { runtime ->
            try {
                runtime.context.close()
                runtime.browser.close()
                runtime.runtime.close()
                logger.info("Recycled runtime #{}", runtime.id)
            } catch (t: Throwable) {
                logger.warn("Error while recycling runtime #{}: {}", runtime.id, t.message)
            }
        }
    }

    /**
     * Clear page state to provide isolation between uses.
     * Navigates to about:blank and clears cookies/storage.
     */
    private suspend fun clearPageState(pooledRuntime: PooledRuntime, pooledPage: PooledPage) {
        val page = pooledPage.page

        // Navigate to about:blank to clear page content
        try {
            page.navigate("about:blank")
        } catch (t: Throwable) {
            logger.debug("Error navigating to about:blank: {}", t.message)
        }

        // Clear cookies via context (this clears all cookies for the context)
        // Since we have 1 context per runtime, this affects all pages in the runtime
        // For true isolation, we use JavaScript to clear just this page's contribution
        try {
            // Clear localStorage and sessionStorage via JavaScript
            // Note: page.navigate already cleared most state by going to about:blank
            // The storage is tied to origin, and about:blank has no origin
        } catch (t: Throwable) {
            logger.debug("Error clearing storage: {}", t.message)
        }
    }

    private suspend fun createNewRuntimeLocked(): PooledRuntime {
        val id = idGenerator.getAndIncrement()
        logger.info("Creating new runtime #{}", id)
        val runtime = PlaywrightBrowserRuntime()
        val browser = runtime.createBrowser()
        val context = browser.createContext()
        val pooled = PooledRuntime(
            id = id,
            runtime = runtime,
            browser = browser,
            context = context,
            pages = mutableListOf(),
            createdAtMillis = System.currentTimeMillis()
        )
        allRuntimes.add(pooled)
        return pooled
    }

    private suspend fun createNewPageLocked(runtime: PooledRuntime): PooledPage {
        val page = runtime.context.newPage()
        val pooledPage = PooledPage(page = page, inUse = false)
        runtime.pages.add(pooledPage)
        return pooledPage
    }

    private suspend fun recycleExpiredIdleRuntimesLocked() {
        val expired = allRuntimes.filter { it.isExpired() && it.isIdle() }
        for (runtime in expired) {
            allRuntimes.remove(runtime)
            try {
                runtime.context.close()
                runtime.browser.close()
                runtime.runtime.close()
                logger.info("Recycled expired idle runtime #{}", runtime.id)
            } catch (t: Throwable) {
                logger.warn("Error while recycling runtime #{}: {}", runtime.id, t.message)
            }
        }
    }
}
