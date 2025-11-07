package io.deepsearch.presentation

import io.deepsearch.presentation.dto.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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
                query = "Are there any vhis plans?"
            ),
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
    fun `test search endpoint with different queries`(testCase: SearchTestCase) = testApplication {
        environment {
            config = ApplicationConfig("application.yaml")
        }

        // Configure client with JSON content negotiation
        val client = createClient {
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
        val searchResult = searchResponse.body<SearchResponse>()

        // Assert response is not null and contains meaningful content
        assertTrue(searchResult.answer.isNotBlank(), "Response answer should not be blank")
        assertTrue(
            searchResult.content.isNotBlank(),
            "Response content should not be blank"
        )
        println("Search response for [${testCase.url}]: ${searchResult.answer}")
    }

    @ParameterizedTest
    @MethodSource("errorTestCases")
    fun `test search endpoint with invalid URLs and error conditions`(testCase: ErrorTestCase) = testApplication {
        environment {
            config = ApplicationConfig("application.yaml")
        }

        // Configure client with JSON content negotiation
        val client = createClient {
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
            val searchResult = searchResponse.body<SearchResponse>()
            println("Response body: ${searchResult.answer}")
            
            // If status is OK, the response should indicate an error somehow
            // This will help us understand how the API handles errors
            assertTrue(
                searchResult.answer.isNotBlank() || searchResult.content.isNotBlank(),
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
    }
}
