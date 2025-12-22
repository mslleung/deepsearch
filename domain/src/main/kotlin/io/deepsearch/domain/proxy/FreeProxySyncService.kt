package io.deepsearch.domain.proxy

import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Service that synchronizes the free proxy list from ProxyScrape every 2 minutes.
 *
 * Maintains an in-memory list of healthy proxies grouped by country.
 * Failed proxies are tracked and excluded until the next sync cycle.
 *
 * @see https://docs.proxyscrape.com/#1ec9e5ed-0dce-4511-91e1-ebe99f7bd88d
 */
class FreeProxySyncService(
    applicationCoroutineScope: IApplicationCoroutineScope
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val scope: CoroutineScope = applicationCoroutineScope.scope

    // ==================== Configuration ====================

    companion object {
        private const val PROXYSCRAPE_INFO_URL =
            "https://api.proxyscrape.com/v4/free-proxy-list/get?request=proxyinfo"
        private const val PROXYSCRAPE_PROXIES_URL =
            "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&proxy_format=protocolipport&format=text&country="
        
        private val SYNC_INTERVAL = 2.minutes
        private val INITIAL_DELAY = 5.seconds
        private val REQUEST_TIMEOUT = 30.seconds
        private val INTER_REQUEST_DELAY = 100L // ms between country fetches
        
        /** Maximum countries to fetch proxies from (limits API calls per sync) */
        private const val MAX_COUNTRIES = 20
    }

    // ==================== HTTP Client ====================

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
            requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
            socketTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
        }
    }

    // ==================== State ====================

    /**
     * Proxies grouped by country code. Immutable map replaced atomically on sync.
     */
    @Volatile
    private var proxiesByCountry: Map<String, List<FreeProxy>> = emptyMap()

    /**
     * Proxy URLs that have failed since last sync. Cleared on each successful sync.
     */
    private val failedProxyUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Timestamp of last successful sync (epoch milliseconds).
     */
    @Volatile
    private var lastSyncTimeMs: Long = 0

    // ==================== Public API: Proxy Access ====================

    /**
     * Get proxies for a specific country, excluding failed ones.
     */
    fun getProxiesForCountry(countryCode: String): List<FreeProxy> {
        val countryProxies = proxiesByCountry[countryCode.uppercase()] ?: return emptyList()
        return countryProxies.filterNot { failedProxyUrls.contains(it.url) }
    }

    /**
     * Get all countries that have at least one available (non-failed) proxy.
     */
    fun getAvailableCountries(): Set<String> {
        return proxiesByCountry.keys.filter { country ->
            proxiesByCountry[country]?.any { !failedProxyUrls.contains(it.url) } == true
        }.toSet()
    }

    /**
     * Total number of available proxies (excluding failed ones).
     */
    val availableProxyCount: Int
        get() = proxiesByCountry.values.sumOf { proxies ->
            proxies.count { !failedProxyUrls.contains(it.url) }
        }

    /**
     * Number of proxies marked as failed since last sync.
     */
    val failedProxyCount: Int
        get() = failedProxyUrls.size

    // ==================== Public API: Proxy Health ====================

    /**
     * Mark a proxy as failed by URL. Excluded from selection until next sync.
     */
    fun markProxyFailed(proxyUrl: String) {
        if (failedProxyUrls.add(proxyUrl)) {
            logger.debug("Marked proxy as failed: {} (total failed: {})", proxyUrl, failedProxyUrls.size)
        }
    }

    // ==================== Public API: Sync Control ====================

    /**
     * Manually trigger a sync (useful for testing or forced refresh).
     */
    suspend fun forceSync() {
        syncProxyList()
    }

    /**
     * Get sync status for monitoring/debugging.
     */
    fun getSyncStatus(): SyncStatus {
        val currentProxies = proxiesByCountry
        return SyncStatus(
            lastSyncTimeMs = lastSyncTimeMs,
            totalProxies = currentProxies.values.sumOf { it.size },
            availableProxies = availableProxyCount,
            failedProxies = failedProxyCount,
            countryCount = currentProxies.size,
            countries = currentProxies.keys.toList()
        )
    }

    // ==================== Background Sync ====================

    init {
        scope.launch {
            logger.info("Starting FreeProxySyncService with {} sync interval", SYNC_INTERVAL)
            
            delay(INITIAL_DELAY)
            syncProxyList()

            while (isActive) {
                delay(SYNC_INTERVAL)
                syncProxyList()
            }
        }
    }

    /**
     * Fetch proxy list from ProxyScrape and update in-memory store.
     */
    private suspend fun syncProxyList() {
        logger.debug("Starting proxy list sync from ProxyScrape...")

        try {
            val countries = fetchAvailableCountries()
            if (countries.isEmpty()) {
                logger.warn("No countries available from ProxyScrape")
                return
            }
            
            logger.debug("Found {} available countries", countries.size)

            val newProxiesByCountry = fetchProxiesForCountries(countries.take(MAX_COUNTRIES))
            
            if (newProxiesByCountry.isEmpty()) {
                logger.warn("No proxies fetched from any country")
                return
            }

            // Atomically update state
            proxiesByCountry = newProxiesByCountry
            failedProxyUrls.clear()
            lastSyncTimeMs = System.currentTimeMillis()

            val totalProxies = newProxiesByCountry.values.sumOf { it.size }
            logger.info(
                "Proxy list sync complete: {} proxies across {} countries",
                totalProxies,
                newProxiesByCountry.size
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to sync proxy list: {}", e.message)
        }
    }

    private suspend fun fetchAvailableCountries(): List<String> {
        val response = httpClient.get(PROXYSCRAPE_INFO_URL)
        
        if (response.status.value != 200) {
            logger.warn("ProxyScrape proxyinfo returned status: {}", response.status)
            return emptyList()
        }

        return try {
            val proxyInfo = json.decodeFromString<ProxyInfoResponse>(response.bodyAsText())
            proxyInfo.countries?.keys?.toList() ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to parse proxyinfo response: {}", e.message)
            emptyList()
        }
    }

    private suspend fun fetchProxiesForCountries(countries: List<String>): Map<String, List<FreeProxy>> {
        val result = mutableMapOf<String, List<FreeProxy>>()
        
        for (country in countries) {
            try {
                val proxies = fetchProxiesForCountry(country)
                if (proxies.isNotEmpty()) {
                    result[country] = proxies
                }
                delay(INTER_REQUEST_DELAY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Failed to fetch proxies for {}: {}", country, e.message)
            }
        }
        
        return result
    }

    private suspend fun fetchProxiesForCountry(countryCode: String): List<FreeProxy> {
        val response = httpClient.get("$PROXYSCRAPE_PROXIES_URL$countryCode")
        
        if (response.status.value != 200) {
            return emptyList()
        }

        return response.bodyAsText()
            .lineSequence()
            .mapNotNull { line -> FreeProxy.parse(line.trim(), countryCode.uppercase()) }
            .toList()
    }

    // ==================== Data Classes ====================

    data class SyncStatus(
        val lastSyncTimeMs: Long,
        val totalProxies: Int,
        val availableProxies: Int,
        val failedProxies: Int,
        val countryCount: Int,
        val countries: List<String>
    )
}

@Serializable
private data class ProxyInfoResponse(
    val countries: Map<String, Int>? = null
)

