package io.deepsearch.presentation

import io.deepsearch.application.services.ISearchFlowEventService
import io.deepsearch.domain.models.entities.SearchFlowEvent
import io.deepsearch.domain.models.entities.SearchFlowEventType
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.presentation.dto.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test that triggers a search and captures all SearchFlowEvents for timeline analysis.
 * This allows testing the main server while still being able to analyze the search timeline
 * without needing the admin server.
 */
class SearchTimelineTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `test search and capture timeline events`() {
        val testApp = TestApplication {
            environment {
                config = ApplicationConfig("application.yaml")
            }
        }

        runBlocking {
            testApp.start()
            try {
                // Configure client with JSON content negotiation
                val client = testApp.createClient {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                prettyPrint = true
                                isLenient = true
                            }
                        )
                    }
                }

                // Step 1: Register a new user
                val testEmail = "timeline-test-${System.currentTimeMillis()}@example.com"
                val registerResponse = client.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RegisterRequest(
                            email = testEmail,
                            password = "testpassword123"
                        )
                    )
                }
                assertEquals(HttpStatusCode.Created, registerResponse.status, "User registration should succeed")

                // Step 2: Login to get JWT token
                val loginResponse = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        LoginRequest(
                            email = testEmail,
                            password = "testpassword123"
                        )
                    )
                }
                assertEquals(HttpStatusCode.OK, loginResponse.status, "Login should succeed")
                val loginData = loginResponse.body<LoginResponse>()
                val jwtToken = loginData.token

                // Step 3: Create an API key using the JWT token
                val createKeyResponse = client.post("/api/keys") {
                    bearerAuth(jwtToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateApiKeyRequest(name = "Timeline Test API Key"))
                }
                assertEquals(HttpStatusCode.Created, createKeyResponse.status, "API key creation should succeed")
                val createKeyData = createKeyResponse.body<CreateApiKeyResponse>()
                val apiKey = createKeyData.rawKey

                // Step 4: Execute search request
                val searchRequest = SearchRequest(
                    query = "Who is the CEO?",
                    url = "https://www.otandp.com/"
                )

                println("\n${"=".repeat(80)}")
                println("SEARCH TIMELINE TEST")
                println("${"=".repeat(80)}")
                println("URL: ${searchRequest.url}")
                println("Query: ${searchRequest.query}")
                println("${"=".repeat(80)}\n")

                val searchStartTime = System.currentTimeMillis()
                val searchResponse = client.post("/api/search") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(searchRequest)
                }
                val searchEndTime = System.currentTimeMillis()

                assertEquals(HttpStatusCode.OK, searchResponse.status, "Search should succeed")
                val searchResult = searchResponse.body<QuerySessionDetailDto>()

                println("Search completed in ${searchEndTime - searchStartTime}ms")
                println("Session ID: ${searchResult.id}")
                println("Answer found: ${searchResult.answerFound}")
                println("Answer preview: ${searchResult.answer?.take(200)}...")
                println()

                // Step 5: Wait briefly for async event persistence (fire-and-forget uses Dispatchers.IO)
                delay(500)

                // Step 6: Get ISearchFlowEventService from Koin and fetch timeline events
                val searchFlowEventService = GlobalContext.get().get<ISearchFlowEventService>()
                val sessionId = QuerySessionId(searchResult.id)
                val events = searchFlowEventService.getEventsForSession(sessionId)

                // Step 7: Print timeline analysis
                printTimelineAnalysis(events, searchResult)

                // Assertions
                assertTrue(events.isNotEmpty(), "Should have captured timeline events")
                assertTrue(
                    events.any { it.eventType == SearchFlowEventType.SESSION_STARTED },
                    "Should have SESSION_STARTED event"
                )
                assertTrue(
                    events.any { it.eventType == SearchFlowEventType.SESSION_COMPLETED || it.eventType == SearchFlowEventType.SESSION_ERROR },
                    "Should have SESSION_COMPLETED or SESSION_ERROR event"
                )

            } finally {
                testApp.stop()
            }
        }
    }

    private fun printTimelineAnalysis(events: List<SearchFlowEvent>, searchResult: QuerySessionDetailDto) {
        println("\n${"=".repeat(80)}")
        println("TIMELINE ANALYSIS")
        println("${"=".repeat(80)}")
        println("Total events captured: ${events.size}")
        println()

        // Group events by type for summary
        val eventsByType = events.groupBy { it.eventType }
        println("Event counts by type:")
        eventsByType.entries.sortedBy { it.key.ordinal }.forEach { (type, typeEvents) ->
            println("  ${type.name}: ${typeEvents.size}")
        }
        println()

        // Calculate session duration from events
        val firstTimestamp = events.minOfOrNull { it.timestampMs }
        val lastTimestamp = events.maxOfOrNull { it.timestampMs }
        if (firstTimestamp != null && lastTimestamp != null) {
            println("Session duration (from events): ${lastTimestamp - firstTimestamp}ms")
        }
        println()

        // Print detailed timeline
        println("${"─".repeat(80)}")
        println("DETAILED EVENT TIMELINE")
        println("${"─".repeat(80)}")
        
        val sortedEvents = events.sortedBy { it.timestampMs }
        val baseTimestamp = sortedEvents.firstOrNull()?.timestampMs ?: 0L

        sortedEvents.forEachIndexed { index, event ->
            val relativeTime = event.timestampMs - baseTimestamp
            val durationStr = event.durationMs?.let { " (duration: ${it}ms)" } ?: ""
            
            println("\n[${index + 1}] +${relativeTime}ms - ${event.eventType.name}$durationStr")
            
            event.url?.let { println("    URL: $it") }
            event.query?.let { println("    Query: $it") }
            event.title?.let { println("    Title: $it") }
            event.description?.let { desc -> 
                if (desc.length > 100) {
                    println("    Description: ${desc.take(100)}...")
                } else {
                    println("    Description: $desc")
                }
            }
            
            if (event.metadata.isNotEmpty()) {
                println("    Metadata:")
                event.metadata.forEach { (key, value) ->
                    val valueStr = when (value) {
                        is List<*> -> value.take(3).joinToString(", ") + if (value.size > 3) "..." else ""
                        is String -> if (value.length > 80) value.take(80) + "..." else value
                        else -> value.toString()
                    }
                    println("      $key: $valueStr")
                }
            }
        }

        // Print URL processing summary
        println("\n${"─".repeat(80)}")
        println("URL PROCESSING SUMMARY")
        println("${"─".repeat(80)}")
        
        val urlEvents = events.filter { it.eventType.name.startsWith("URL_") }
        val uniqueUrls = urlEvents.mapNotNull { it.url }.distinct()
        println("URLs processed: ${uniqueUrls.size}")
        
        uniqueUrls.forEach { url ->
            val urlSpecificEvents = urlEvents.filter { it.url == url }
            val eventTypes = urlSpecificEvents.map { it.eventType.name.removePrefix("URL_") }
            println("  $url")
            println("    Events: ${eventTypes.joinToString(" → ")}")
        }

        // Print synthesis summary
        println("\n${"─".repeat(80)}")
        println("SYNTHESIS SUMMARY")
        println("${"─".repeat(80)}")
        
        val synthesisEvents = events.filter { 
            it.eventType == SearchFlowEventType.SYNTHESIS_STARTED || 
            it.eventType == SearchFlowEventType.SYNTHESIS_COMPLETE 
        }
        println("Synthesis iterations: ${synthesisEvents.count { it.eventType == SearchFlowEventType.SYNTHESIS_COMPLETE }}")
        
        synthesisEvents.forEach { event ->
            val relativeTime = event.timestampMs - baseTimestamp
            println("  +${relativeTime}ms - ${event.eventType.name}")
            event.durationMs?.let { println("    Duration: ${it}ms") }
        }

        // Print follow-up queries if any
        val followUpEvents = events.filter { it.eventType == SearchFlowEventType.FOLLOW_UP_QUERY_GENERATED }
        if (followUpEvents.isNotEmpty()) {
            println("\n${"─".repeat(80)}")
            println("FOLLOW-UP QUERIES")
            println("${"─".repeat(80)}")
            followUpEvents.forEach { event ->
                @Suppress("UNCHECKED_CAST")
                val queries = event.metadata["followUpQueries"] as? List<String> ?: emptyList()
                queries.forEach { query ->
                    println("  - $query")
                }
            }
        }

        println("\n${"=".repeat(80)}")
        println("END OF TIMELINE ANALYSIS")
        println("${"=".repeat(80)}\n")
    }
}
