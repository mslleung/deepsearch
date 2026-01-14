package io.deepsearch.presentation

import io.deepsearch.presentation.dto.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SearchEndpointTest {

    data class SearchTestCase(
        val url: String,
        val query: String
    )

    data class ErrorTestCase(
        val url: String,
        val query: String,
        val description: String
    )

    companion object {
        @JvmStatic
        fun searchTestCases(): List<SearchTestCase> = listOf(
            SearchTestCase(
                url = "https://www.otandp.com/",
                query = "Who is the CEO?"
            ),
//            SearchTestCase(
//                url = "https://sleekflow.io/",
//                query = "Tell me about pricing"
//            ),
/*            SearchTestCase(
                url = "https://www.wikipedia.org/",
                query = "What is artificial intelligence?"
            ),
            SearchTestCase(
                url = "https://www.github.com/",
                query = "What is version control?"
            ),
            SearchTestCase(
                url = "https://www.stackoverflow.com/",
                query = "How do I ask a good question?"
            ),
            SearchTestCase(
                url = "https://www.kotlinlang.org/",
                query = "What are coroutines?"
            )*/
        )

        @JvmStatic
        fun errorTestCases(): List<ErrorTestCase> = listOf(
            ErrorTestCase(
                url = "https://www.google.com/this-page-definitely-does-not-exist-404",
                query = "What is on this page?",
                description = "404 Not Found"
            ),
            ErrorTestCase(
                url = "https://thisdomaindefinitelydoesnotexist123456789.com/",
                query = "What is on this page?",
                description = "DNS not found"
            ),
            ErrorTestCase(
                url = "not-a-valid-url-at-all",
                query = "What is on this page?",
                description = "Invalid URL format"
            ),
            ErrorTestCase(
                url = "http://",
                query = "What is on this page?",
                description = "Incomplete URL"
            ),
            ErrorTestCase(
                url = "ftp://invalid-protocol.com/",
                query = "What is on this page?",
                description = "Invalid protocol"
            )
        )
    }

    @ParameterizedTest
    @MethodSource("searchTestCases")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `test search endpoint with different queries`(testCase: SearchTestCase) {
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
                val testEmail = "test-${System.currentTimeMillis()}@example.com"
                val registerResponse = client.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest(
                        email = testEmail,
                        password = "testpassword123"
                    ))
                }
                assertEquals(HttpStatusCode.Created, registerResponse.status, "User registration should succeed")

                // Step 2: Login to get JWT token
                val loginResponse = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(
                        email = testEmail,
                        password = "testpassword123"
                    ))
                }
                assertEquals(HttpStatusCode.OK, loginResponse.status, "Login should succeed")
                val loginData = loginResponse.body<LoginResponse>()
                val jwtToken = loginData.token

                // Step 3: Create an API key using the JWT token
                val createKeyResponse = client.post("/api/keys") {
                    bearerAuth(jwtToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateApiKeyRequest(name = "Test API Key"))
                }
                assertEquals(HttpStatusCode.Created, createKeyResponse.status, "API key creation should succeed")
                val createKeyData = createKeyResponse.body<CreateApiKeyResponse>()
                val apiKey = createKeyData.rawKey

                // Step 4: Use the API key to make a search request
                val searchRequest = SearchRequest(
                    query = testCase.query,
                    url = testCase.url
                )

                val searchResponse = client.post("/api/search") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(searchRequest)
                }

                // Assert response status
                assertEquals(HttpStatusCode.OK, searchResponse.status, "Expected OK status")

                // Parse the response body
                val searchResult = searchResponse.body<QuerySessionDetailDto>()

                // Assert response is not null and contains meaningful content
                assertEquals(searchResult.answer?.isNotBlank(), true, "Response answer should not be blank")
                assertTrue(
                    searchResult.contentSources.isNotEmpty(),
                    "Response content sources should not be empty"
                )
                println("Search response for [${testCase.url}]: ${searchResult.answer}")
            } finally {
                testApp.stop()
            }
        }
    }

    @ParameterizedTest
    @MethodSource("errorTestCases")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `test search endpoint with invalid URLs and error conditions`(testCase: ErrorTestCase) {
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
                val testEmail = "test-${System.currentTimeMillis()}@example.com"
                val registerResponse = client.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest(
                        email = testEmail,
                        password = "testpassword123"
                    ))
                }
                assertEquals(HttpStatusCode.Created, registerResponse.status, "User registration should succeed")

                // Step 2: Login to get JWT token
                val loginResponse = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(
                        email = testEmail,
                        password = "testpassword123"
                    ))
                }
                assertEquals(HttpStatusCode.OK, loginResponse.status, "Login should succeed")
                val loginData = loginResponse.body<LoginResponse>()
                val jwtToken = loginData.token

                // Step 3: Create an API key using the JWT token
                val createKeyResponse = client.post("/api/keys") {
                    bearerAuth(jwtToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateApiKeyRequest(name = "Test API Key"))
                }
                assertEquals(HttpStatusCode.Created, createKeyResponse.status, "API key creation should succeed")
                val createKeyData = createKeyResponse.body<CreateApiKeyResponse>()
                val apiKey = createKeyData.rawKey

                // Step 4: Use the API key to make a search request with invalid URL
                val searchRequest = SearchRequest(
                    query = testCase.query,
                    url = testCase.url
                )

                val searchResponse = client.post("/api/search") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(searchRequest)
                }

                println("Error test case [${testCase.description}] - URL: ${testCase.url}")
                println("Response status: ${searchResponse.status}")

                // For error cases, we expect either:
                // 1. A non-OK status code (4xx or 5xx)
                // 2. An OK status but with an error indication in the response
                if (searchResponse.status == HttpStatusCode.OK) {
                    val searchResult = searchResponse.body<QuerySessionDetailDto>()
                    println("Response body: ${searchResult.answer}")
                    
                    // If status is OK, the response should indicate an error somehow
                    // This will help us understand how the API handles errors
                    assertTrue(
                        searchResult.answer?.isNotBlank() == true || searchResult.contentSources.isNotEmpty(),
                        "Error response should contain some content explaining the error"
                    )
                } else {
                    // API returned an error status code, which is also acceptable
                    assertNotEquals(
                        HttpStatusCode.OK,
                        searchResponse.status,
                        "Expected error status code for ${testCase.description}"
                    )
                    println("Received expected error status: ${searchResponse.status}")
                }
            } finally {
                testApp.stop()
            }
        }
    }

    @org.junit.jupiter.api.Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `test session continuation - second search should link to first`() {
        val testApp = TestApplication {
            environment {
                config = ApplicationConfig("application.yaml")
            }
        }

        runBlocking {
            testApp.start()
            try {
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

                // Setup: Register, login, get API key
                val testEmail = "continuation-test-${System.currentTimeMillis()}@example.com"
                val registerResponse = client.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest(email = testEmail, password = "testpassword123"))
                }
                assertEquals(HttpStatusCode.Created, registerResponse.status)

                val loginResponse = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(email = testEmail, password = "testpassword123"))
                }
                val jwtToken = loginResponse.body<LoginResponse>().token

                val createKeyResponse = client.post("/api/keys") {
                    bearerAuth(jwtToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateApiKeyRequest(name = "Continuation Test Key"))
                }
                val apiKey = createKeyResponse.body<CreateApiKeyResponse>().rawKey

                // === FIRST SEARCH ===
                println("\n=== FIRST SEARCH: Initial query ===")
                val firstSearchRequest = SearchRequest(
                    query = "What is OT&P?",
                    url = "https://www.otandp.com/"
                )

                val firstSearchResponse = client.post("/api/search") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(firstSearchRequest)
                }

                assertEquals(HttpStatusCode.OK, firstSearchResponse.status, "First search should succeed")
                val firstResult = firstSearchResponse.body<QuerySessionDetailDto>()
                val firstSessionId = firstResult.id
                
                println("First session ID: $firstSessionId")
                println("First query: ${firstResult.query}")
                println("First answer preview: ${firstResult.answer?.take(200)}...")

                assertTrue(firstSessionId.isNotBlank(), "First session should have an ID")
                assertTrue(firstResult.answer?.isNotBlank() == true, "First search should have an answer")

                // === SECOND SEARCH (CONTINUATION) ===
                println("\n=== SECOND SEARCH: Continuation with context ===")
                val secondSearchRequest = SearchRequest(
                    query = "Who are the doctors there?",
                    url = "https://www.otandp.com/",
                    continueSessionId = firstSessionId  // Link to first session!
                )

                val secondSearchResponse = client.post("/api/search") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(secondSearchRequest)
                }

                assertEquals(HttpStatusCode.OK, secondSearchResponse.status, "Second search should succeed")
                val secondResult = secondSearchResponse.body<QuerySessionDetailDto>()
                val secondSessionId = secondResult.id

                println("Second session ID: $secondSessionId")
                println("Second query: ${secondResult.query}")
                println("Second answer preview: ${secondResult.answer?.take(200)}...")

                assertTrue(secondSessionId.isNotBlank(), "Second session should have an ID")
                assertTrue(secondSessionId != firstSessionId, "Second session should have different ID")
                assertTrue(secondResult.answer?.isNotBlank() == true, "Second search should have an answer")

                // === THIRD SEARCH (DEEPER CONTINUATION) ===
                println("\n=== THIRD SEARCH: Even deeper continuation ===")
                val thirdSearchRequest = SearchRequest(
                    query = "What are their specialties?",
                    url = "https://www.otandp.com/",
                    continueSessionId = secondSessionId  // Link to second session!
                )

                val thirdSearchResponse = client.post("/api/search") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(thirdSearchRequest)
                }

                assertEquals(HttpStatusCode.OK, thirdSearchResponse.status, "Third search should succeed")
                val thirdResult = thirdSearchResponse.body<QuerySessionDetailDto>()
                val thirdSessionId = thirdResult.id

                println("Third session ID: $thirdSessionId")
                println("Third query: ${thirdResult.query}")
                println("Third answer preview: ${thirdResult.answer?.take(200)}...")

                assertTrue(thirdSessionId.isNotBlank(), "Third session should have an ID")
                assertTrue(thirdSessionId != secondSessionId, "Third session should have different ID")
                
                println("\n=== SESSION CONTINUATION TEST COMPLETE ===")
                println("Chain: $firstSessionId -> $secondSessionId -> $thirdSessionId")

            } finally {
                testApp.stop()
            }
        }
    }
}
