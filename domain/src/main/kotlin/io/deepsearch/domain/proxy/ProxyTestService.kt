package io.deepsearch.domain.proxy

import io.deepsearch.domain.http.IProxyAwareHttpClientFactory
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Result of a proxy connection test.
 */
@Serializable
data class ProxyTestResult(
    val success: Boolean,
    val externalIp: String? = null,
    val latencyMs: Long? = null,
    val errorMessage: String? = null
)

/**
 * Service for testing proxy connectivity.
 */
interface IProxyTestService {
    /**
     * Test if a proxy is reachable by making an HTTP request through it.
     *
     * @param proxyUrl The proxy URL to test (e.g., "socks5://1.2.3.4:1080")
     * @return ProxyTestResult with success status, external IP, and latency
     */
    suspend fun testProxy(proxyUrl: String): ProxyTestResult
}

class ProxyTestService(
    private val httpClientFactory: IProxyAwareHttpClientFactory
) : IProxyTestService {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val TEST_URL = "https://ipinfo.io/ip"
        private const val TIMEOUT_MS = 10_000L
    }

    override suspend fun testProxy(proxyUrl: String): ProxyTestResult {
        logger.info("Testing proxy connection: {}", proxyUrl.substringBefore("@").substringAfter("://"))

        val proxyConfig = ProxyConfiguration.Custom(proxyUrl)

        return try {
            val client = httpClientFactory.createClient(proxyConfig) {
                config {
                    connectTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    readTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    writeTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }

            var externalIp: String? = null
            val latencyMs = measureTimeMillis {
                client.use { httpClient ->
                    val response = httpClient.get(TEST_URL)
                    if (response.status.isSuccess()) {
                        externalIp = response.bodyAsText().trim()
                    } else {
                        throw Exception("HTTP ${response.status.value}: ${response.status.description}")
                    }
                }
            }

            logger.info("Proxy test successful. External IP: {}, Latency: {} ms", externalIp, latencyMs)
            ProxyTestResult(
                success = true,
                externalIp = externalIp,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> 
                    "Connection timeout - proxy may be unreachable"
                e.message?.contains("refused", ignoreCase = true) == true -> 
                    "Connection refused - proxy server not accepting connections"
                e.message?.contains("resolve", ignoreCase = true) == true -> 
                    "DNS resolution failed - check proxy hostname"
                else -> e.message ?: "Unknown error"
            }

            logger.warn("Proxy test failed: {}", errorMessage)
            ProxyTestResult(
                success = false,
                errorMessage = errorMessage
            )
        }
    }
}

