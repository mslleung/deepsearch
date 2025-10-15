package io.deepsearch.presentation

import io.deepsearch.presentation.dto.SearchRequest
import io.deepsearch.presentation.dto.SearchResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchEndpointTest {

    @Test
    fun `test search endpoint with OT&P body check query`() = testApplication {
        application {
            module()
        }

        // Configure client with JSON content negotiation
        val client = createClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
        }

        // Prepare the search request
        val request = SearchRequest(
            query = "Does the standard body check package include testing \"Stool: Occult Blood?\"",
            url = "https://www.otandp.com/"
        )

        // Make the request
        val response = client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert response status
        assertEquals(HttpStatusCode.OK, response.status, "Expected OK status")

        // Parse the response body
        val searchResponse = response.body<SearchResponse>()
        
        // Assert response is not null and contains meaningful content
        assertNotNull(searchResponse.response, "Response content should not be null")
        assertTrue(
            searchResponse.response.isNotBlank(),
            "Response content should not be blank"
        )
//        println("Search response: ${searchResponse.response}")
    }
}

