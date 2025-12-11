package io.deepsearch.domain.http

import io.deepsearch.domain.config.ProxyrackHttpConfig
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import okhttp3.Credentials
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Factory for creating HTTP clients with proxy configuration.
 * 
 * This factory creates Ktor HTTP clients backed by OkHttp with proxy settings
 * applied based on the provided ProxyConfiguration.
 */
interface IProxyAwareHttpClientFactory {
    /**
     * Create an HTTP client with the specified proxy configuration.
     * 
     * @param proxyConfig The proxy configuration to apply
     * @param configure Additional OkHttp configuration
     * @return A configured HttpClient
     */
    fun createClient(
        proxyConfig: ProxyConfiguration,
        configure: OkHttpConfig.() -> Unit = {}
    ): HttpClient
}

class ProxyAwareHttpClientFactory(
    private val proxyrackConfig: ProxyrackHttpConfig
) : IProxyAwareHttpClientFactory {
    
    private val logger = LoggerFactory.getLogger(this::class.java)
    
    override fun createClient(
        proxyConfig: ProxyConfiguration,
        configure: OkHttpConfig.() -> Unit
    ): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                // Apply proxy configuration
                when (proxyConfig) {
                    is ProxyConfiguration.None -> {
                        // No proxy - direct connection
                        logger.debug("Creating HTTP client without proxy")
                    }
                    is ProxyConfiguration.Custom -> {
                        configureProxy(proxyConfig.proxyUrl)
                    }
                    is ProxyConfiguration.Included -> {
                        configureProxy(proxyrackConfig.toProxyUrl())
                    }
                }
                
                // Apply additional configuration
                configure()
            }
            
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
    }
    
    private fun OkHttpConfig.configureProxy(proxyUrl: String) {
        logger.debug("Configuring HTTP client with proxy: {}", proxyUrl.substringBefore("@").substringAfter("://"))
        
        try {
            val uri = URI(proxyUrl)
            val proxyType = when (uri.scheme.lowercase()) {
                "http", "https" -> Proxy.Type.HTTP
                "socks5", "socks" -> Proxy.Type.SOCKS
                else -> {
                    logger.warn("Unknown proxy scheme: {}, defaulting to HTTP", uri.scheme)
                    Proxy.Type.HTTP
                }
            }
            
            // Extract host and port
            val host = uri.host ?: throw IllegalArgumentException("Proxy URL missing host")
            val port = if (uri.port > 0) uri.port else 8080
            
            // Configure OkHttp with proxy
            config {
                proxy(Proxy(proxyType, InetSocketAddress(host, port)))
                
                // Configure authentication if present in URL
                val userInfo = uri.userInfo
                if (userInfo != null) {
                    val parts = userInfo.split(":", limit = 2)
                    if (parts.size == 2) {
                        val username = parts[0]
                        val password = parts[1]
                        proxyAuthenticator { _, response ->
                            response.request.newBuilder()
                                .header("Proxy-Authorization", Credentials.basic(username, password))
                                .build()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to configure proxy from URL: {}", e.message)
            throw IllegalArgumentException("Invalid proxy URL: ${e.message}", e)
        }
    }
}

