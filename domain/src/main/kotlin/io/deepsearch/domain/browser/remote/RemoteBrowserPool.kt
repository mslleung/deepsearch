package io.deepsearch.domain.browser.remote

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.remote.dto.*
import io.deepsearch.domain.config.DeepSearchBrowserConfig
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.proxy.ProxyConfiguration
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browser pool using REST API to communicate with deepsearch-browser service.
 *
 * - Acquires sessions via POST /sessions
 * - Sends commands via POST /sessions/{id}/command
 * - Releases sessions via DELETE /sessions/{id}
 */
class RemoteBrowserPool(
    config: DeepSearchBrowserConfig,
    applicationCoroutineScope: IApplicationCoroutineScope
) : IBrowserPool {

    private val baseUrl = config.url.trimEnd('/')
    private val scope: CoroutineScope = applicationCoroutineScope.scope
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
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
    }

    init {
        logger.info("RemoteBrowserPool connecting to: {}", baseUrl)
    }

    override suspend fun <T> withPage(
        proxyConfig: ProxyConfiguration,
        block: suspend (IBrowserPage) -> T
    ): T {
        // Convert domain ProxyConfiguration to API ProxyConfigDto
        val proxyDto = proxyConfig.toDto()
        
        // Acquire session with proxy configuration
        val acquireResponse = try {
            httpClient.post("$baseUrl/sessions") {
                contentType(ContentType.Application.Json)
                setBody(AcquireRequest(proxy = proxyDto))
            }
        } catch (e: CancellationException) {
            throw e // Preserve cooperative cancellation
        } catch (e: Exception) {
            throw ConnectionLostException("Failed to acquire session: ${e.message}")
        }

        if (!acquireResponse.status.isSuccess()) {
            val errorBody = acquireResponse.bodyAsText()
            throw RemoteBrowserException("ACQUIRE_FAILED", "Failed to acquire session: $errorBody")
        }

        val sessionInfo = acquireResponse.body<AcquireResponse>()
        val sessionId = sessionInfo.sessionId

        logger.debug("Acquired session: {} with proxy: {}", sessionId, proxyConfig::class.simpleName)

        val released = AtomicBoolean(false)

        val page = RemoteBrowserPage(
            sessionId = sessionId,
            json = json,
            onClose = {
                if (released.compareAndSet(false, true)) {
                    releaseSession(sessionId)
                    logger.debug("Released session via close(): {}", sessionId)
                }
            },
            execute = { command -> sendCommand(sessionId, command) }
        )

        return try {
            block(page)
        } finally {
            if (released.compareAndSet(false, true)) {
                try {
                    releaseSession(sessionId)
                    logger.debug("Released session via finally: {}", sessionId)
                } catch (e: Exception) {
                    logger.warn("Failed to release session {}: {}", sessionId, e.message)
                }
            }
        }
    }
    
    /**
     * Convert domain ProxyConfiguration to API DTO.
     */
    private fun ProxyConfiguration.toDto(): ProxyConfigDto? {
        return when (this) {
            is ProxyConfiguration.None -> null
            is ProxyConfiguration.Custom -> ProxyConfigDto(type = "CUSTOM", customUrl = this.proxyUrl)
            is ProxyConfiguration.Included -> ProxyConfigDto(type = "INCLUDED", customUrl = null)
            is ProxyConfiguration.FreeRotating -> ProxyConfigDto(type = "FREE_ROTATING", customUrl = this.proxyUrl)
        }
    }

    private suspend fun sendCommand(sessionId: String, command: PageCommand): String {
        val response = try {
            httpClient.post("$baseUrl/sessions/$sessionId/command") {
                contentType(ContentType.Application.Json)
                setBody(command)
            }
        } catch (e: CancellationException) {
            throw e // Preserve cooperative cancellation
        } catch (e: Exception) {
            throw ConnectionLostException("Command failed: ${e.message}")
        }

        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.NotFound) {
                throw RemoteBrowserException("SESSION_NOT_FOUND", "Session expired or not found")
            }
            val errorBody = response.bodyAsText()
            throw RemoteBrowserException("COMMAND_FAILED", "Command failed: $errorBody")
        }

        val result = response.body<CommandResponse>()
        
        if (!result.success) {
            throw RemoteBrowserException(
                result.error?.code ?: "COMMAND_FAILED",
                result.error?.message ?: "Command failed"
            )
        }

        return result.data ?: ""
    }

    private suspend fun releaseSession(sessionId: String) {
        withContext(NonCancellable) {
            try {
                httpClient.delete("$baseUrl/sessions/$sessionId")
            } catch (e: Exception) {
                logger.warn("Error releasing session: {}", e.message)
            }
        }
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }
}

// ==================== Request/Response DTOs ====================

@Serializable
private data class AcquireRequest(
    val proxy: ProxyConfigDto? = null
)

@Serializable
private data class ProxyConfigDto(
    val type: String = "NONE",
    val customUrl: String? = null
)

@Serializable
private data class AcquireResponse(
    val sessionId: String,
    val clientId: String
)

@Serializable
private data class CommandResponse(
    val success: Boolean,
    val data: String? = null,
    val error: ErrorInfo? = null
)

@Serializable
private data class ErrorInfo(
    val code: String,
    val message: String
)

/**
 * Exception thrown when a remote browser command fails.
 * 
 * This is the raw exception from the browser service. It is typically wrapped
 * by RemoteBrowserPage into more specific exception types:
 * - ElementOperationException for element-level failures (recoverable)
 * - PageOperationException for page-level failures (typically fatal)
 * 
 * The error code comes from the browser service and can be used for logging/debugging.
 */
class RemoteBrowserException(val code: String, message: String) : Exception(message)

/**
 * Exception thrown when the connection to the browser service is lost.
 * This is always a fatal error that should propagate.
 */
class ConnectionLostException(message: String) : Exception(message)
