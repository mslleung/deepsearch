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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEndpointTest {

    @Test
    fun `test search endpoint with OT&P body check query`() = testApplication {
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
                password = "testpassword123",
                displayName = "Test User"
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
            query = "the standard body check package",
            url = "https://www.otandp.com/"
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
        println("Search response: ${searchResult.answer}")
    }
}

