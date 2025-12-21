package io.deepsearch.domain.proxy

import io.deepsearch.domain.config.DeepSearchBrowserConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Remote implementation of IFreeProxyProvider that communicates with the browser service.
 * 
 * This adapter calls the browser service's proxy management endpoints to:
 * - Select proxies for a domain
 * - Mark proxies as failed
 * - Check proxy availability
 */
class RemoteFreeProxyProvider(
    config: DeepSearchBrowserConfig
) : IFreeProxyProvider {

    private val baseUrl = config.url.trimEnd('/')
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    override suspend fun selectProxiesForDomain(domain: String, count: Int): List<String> {
        return try {
            val response = httpClient.post("$baseUrl/proxies/select") {
                contentType(ContentType.Application.Json)
                setBody(ProxySelectRequest(domain, count))
            }

            if (response.status.isSuccess()) {
                response.body<ProxySelectResponse>().proxies
            } else {
                logger.warn("Failed to select proxies for {}: {}", domain, response.status)
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Error selecting proxies for {}: {}", domain, e.message)
            emptyList()
        }
    }

    override suspend fun releaseProxies(domain: String, proxyUrls: List<String>) {
        try {
            httpClient.post("$baseUrl/proxies/release") {
                contentType(ContentType.Application.Json)
                setBody(ProxyReleaseRequest(domain, proxyUrls))
            }
        } catch (e: Exception) {
            logger.warn("Error releasing proxies for {}: {}", domain, e.message)
        }
    }

    override suspend fun markProxyFailed(domain: String, proxyUrl: String) {
        try {
            httpClient.post("$baseUrl/proxies/failed") {
                contentType(ContentType.Application.Json)
                setBody(ProxyFailedRequest(domain, proxyUrl))
            }
        } catch (e: Exception) {
            logger.warn("Error marking proxy failed for {}: {}", domain, e.message)
        }
    }

    override suspend fun hasAvailableProxies(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/proxies/available")
            if (response.status.isSuccess()) {
                response.body<ProxyAvailableResponse>().available
            } else {
                false
            }
        } catch (e: Exception) {
            logger.warn("Error checking proxy availability: {}", e.message)
            false
        }
    }

    fun close() {
        httpClient.close()
    }
}

// ==================== Request/Response DTOs ====================

@Serializable
private data class ProxySelectRequest(
    val domain: String,
    val count: Int = 3
)

@Serializable
private data class ProxySelectResponse(
    val proxies: List<String>
)

@Serializable
private data class ProxyReleaseRequest(
    val domain: String,
    val proxyUrls: List<String>
)

@Serializable
private data class ProxyFailedRequest(
    val domain: String,
    val proxyUrl: String
)

@Serializable
private data class ProxyAvailableResponse(
    val available: Boolean
)

/**
 * Interface for free proxy provider.
 * Provides proxies for bypass strategy fanout.
 */
interface IFreeProxyProvider {
    /**
     * Select proxies for a domain, ensuring no duplicates with current active connections.
     * 
     * @param domain The domain being accessed
     * @param count Number of proxies to select
     * @return List of proxy URLs
     */
    suspend fun selectProxiesForDomain(domain: String, count: Int): List<String>

    /**
     * Release proxies after use.
     */
    suspend fun releaseProxies(domain: String, proxyUrls: List<String>)

    /**
     * Mark a proxy as failed.
     */
    suspend fun markProxyFailed(domain: String, proxyUrl: String)

    /**
     * Check if proxies are available.
     */
    suspend fun hasAvailableProxies(): Boolean
}

