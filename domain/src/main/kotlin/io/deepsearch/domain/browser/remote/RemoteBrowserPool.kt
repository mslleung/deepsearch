package io.deepsearch.domain.browser.remote

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.remote.dto.*
import io.deepsearch.domain.config.DeepSearchBrowserConfig
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Browser pool with a single persistent WebSocket connection.
 *
 * - Connects on init, reconnects in background if lost
 * - All requests use requestId for correlation
 * - withPage() acquires session, uses it, releases it
 */
class RemoteBrowserPool(
    config: DeepSearchBrowserConfig,
    applicationCoroutineScope: IApplicationCoroutineScope
) : IBrowserPool {

    private val wsUrl = config.url.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
    private val scope: CoroutineScope = applicationCoroutineScope.scope
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets) { pingInterval = 20.seconds }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 300_000
        }
    }

    @Volatile
    private var ws: DefaultClientWebSocketSession? = null

    // Pending requests waiting for response, keyed by requestId
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerMessage>>()

    init {
        logger.info("RemoteBrowserPool connecting to: {}", wsUrl)
        scope.launch { connectionLoop() }
    }

    private suspend fun connectionLoop() {
        while (true) {
            try {
                logger.info("Connecting to browser service...")
                val session = httpClient.webSocketSession(wsUrl)
                ws = session
                logger.info("Connected")

                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        val msg = json.decodeFromString<ServerMessage>(frame.readText())
                        pending.remove(msg.requestId)?.complete(msg)
                    }
                }
            } catch (e: Exception) {
                logger.warn("WebSocket error: {}", e.message)
            }

            // Disconnected - fail all pending requests
            ws = null
            val error = ServerMessage.Error("", "DISCONNECTED", "Connection lost")
            pending.values.forEach { it.complete(error) }
            pending.clear()

            delay(1000)
        }
    }

    private suspend fun send(msg: ClientMessage): ServerMessage {
        val session = ws ?: throw ConnectionLostException("Not connected")
        val deferred = CompletableDeferred<ServerMessage>()
        pending[msg.requestId] = deferred

        try {
            session.send(Frame.Text(json.encodeToString(msg)))
            return deferred.await()
        } catch (e: Exception) {
            pending.remove(msg.requestId)
            throw ConnectionLostException("Send failed: ${e.message}")
        }
    }

    override suspend fun <T> withPage(block: suspend (IBrowserPage) -> T): T {
        // Acquire session
        val acquireResponse = send(ClientMessage.Acquire(UUID.randomUUID().toString()))
        val sessionId = when (acquireResponse) {
            is ServerMessage.Acquired -> acquireResponse.sessionId
            is ServerMessage.Error -> throw RemoteBrowserException(acquireResponse.code, acquireResponse.message)
            else -> throw RemoteBrowserException("UNEXPECTED", "Unexpected response: $acquireResponse")
        }

        logger.debug("Acquired session: {}", sessionId)

        val page = RemoteBrowserPage(sessionId, json) { command ->
            val response = send(ClientMessage.Command(UUID.randomUUID().toString(), sessionId, command))
            when (response) {
                is ServerMessage.Result -> if (response.success) response.data else throw RemoteBrowserException(
                    "COMMAND_FAILED",
                    "Command failed"
                )

                is ServerMessage.Error -> throw RemoteBrowserException(response.code, response.message)
                else -> throw RemoteBrowserException("UNEXPECTED", "Unexpected response")
            }
        }

        return try {
            block(page)
        } finally {
            try {
                send(ClientMessage.Release(UUID.randomUUID().toString(), sessionId))
                logger.debug("Released session: {}", sessionId)
            } catch (e: Exception) {
                logger.warn("Failed to release session {}: {}", sessionId, e.message)
            }
        }
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }
}

class RemoteBrowserException(val code: String, message: String) : Exception(message)
class ConnectionLostException(message: String) : Exception(message)
