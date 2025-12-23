package io.deepsearch.domain.browser.remote

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.remote.dto.*
import io.deepsearch.domain.config.DeepSearchBrowserConfig
import io.deepsearch.domain.config.IApplicationCoroutineScope
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
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
        engine {
            config {
                // Increase connection pool for concurrent browser requests
                // Default is 5 connections per host which causes queuing
                connectionPool(ConnectionPool(
                    maxIdleConnections = 50,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                ))
                
                // Increase dispatcher limits for concurrent requests
                dispatcher(Dispatcher().apply {
                    maxRequests = 100
                    maxRequestsPerHost = 50
                })
            }
        }
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
        proxyUrl: String?,
        block: suspend (IBrowserPage) -> T
    ): T {
        val acquireStart = System.currentTimeMillis()
        
        // Acquire session with proxy URL
        val acquireResponse = try {
            httpClient.post("$baseUrl/sessions") {
                contentType(ContentType.Application.Json)
                setBody(AcquireRequest(proxyUrl = proxyUrl))
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
        val acquireTime = System.currentTimeMillis() - acquireStart

        logger.debug("Acquired session: {} in {}ms with proxy: {}", sessionId, acquireTime, proxyUrl ?: "direct")

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

    private suspend fun sendCommand(sessionId: String, command: PageCommand): String {
        val commandName = command::class.simpleName
        val httpStart = System.currentTimeMillis()
        
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
        
        val httpTime = System.currentTimeMillis() - httpStart

        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.NotFound) {
                throw RemoteBrowserException("SESSION_NOT_FOUND", "Session expired or not found")
            }
            val errorBody = response.bodyAsText()
            throw RemoteBrowserException("COMMAND_FAILED", "Command failed: $errorBody")
        }

        // Measure body reading separately from JSON parsing
        val bodyStart = System.currentTimeMillis()
        val bodyText = response.bodyAsText()
        val bodyReadTime = System.currentTimeMillis() - bodyStart
        
        val parseStart = System.currentTimeMillis()
        val result = json.decodeFromString<CommandResponse>(bodyText)
        val parseTime = System.currentTimeMillis() - parseStart
        
        val dataLength = result.data?.length ?: 0
        val totalTime = httpTime + bodyReadTime + parseTime
        
        if (totalTime > 500) {
            logger.info(
                "sendCommand {} [{}]: http={}ms, bodyRead={}ms, parse={}ms, bodyLen={}, dataLen={}",
                commandName, sessionId, httpTime, bodyReadTime, parseTime, bodyText.length, dataLength
            )
        } else {
            logger.debug(
                "sendCommand {} [{}]: http={}ms, bodyRead={}ms, parse={}ms, dataLen={}",
                commandName, sessionId, httpTime, bodyReadTime, parseTime, dataLength
            )
        }
        
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
    val proxyUrl: String? = null
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
