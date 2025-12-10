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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

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

    override suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T {
        // Acquire session
        val acquireResponse = try {
            httpClient.post("$baseUrl/sessions") {
                contentType(ContentType.Application.Json)
            }
        } catch (e: Exception) {
            throw ConnectionLostException("Failed to acquire session: ${e.message}")
        }

        if (!acquireResponse.status.isSuccess()) {
            val errorBody = acquireResponse.bodyAsText()
            throw RemoteBrowserException("ACQUIRE_FAILED", "Failed to acquire session: $errorBody")
        }

        val sessionInfo = acquireResponse.body<AcquireResponse>()
        val sessionId = sessionInfo.sessionId

        logger.debug("Acquired session: {}", sessionId)

        val page = RemoteBrowserPage(sessionId, json) { command ->
            sendCommand(sessionId, command)
        }

        return try {
            block(page)
        } finally {
            try {
                releaseSession(sessionId)
                logger.debug("Released session: {}", sessionId)
            } catch (e: Exception) {
                logger.warn("Failed to release session {}: {}", sessionId, e.message)
            }
        }
    }

    private suspend fun sendCommand(sessionId: String, command: PageCommand): String {
        val response = try {
            httpClient.post("$baseUrl/sessions/$sessionId/command") {
                contentType(ContentType.Application.Json)
                setBody(command)
            }
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
        try {
            httpClient.delete("$baseUrl/sessions/$sessionId")
        } catch (e: Exception) {
            logger.warn("Error releasing session: {}", e.message)
        }
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }
}

// ==================== Response DTOs ====================

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
