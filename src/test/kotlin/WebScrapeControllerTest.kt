package io.deepsearch

import io.deepsearch.application.dto.WebScrapeRequest
import io.deepsearch.application.dto.WebScrapeResponse
import io.deepsearch.application.services.WebScrapeService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.*

class WebScrapeControllerTest : KoinTest {
    
    private val testJson = Json { ignoreUnknownKeys = true }
    
    @Test
    fun `should successfully scrape website and return response`() = testApplication {
        application {
            module()
        }
        
        // Configure test dependencies with real ADK service
        configureTestDependencies()
        
        client.post("/api/scrape") {
            contentType(ContentType.Application.Json)
            setBody(
                testJson.encodeToString(
                    WebScrapeRequest.serializer(),
                    WebScrapeRequest(
                        url = "https://example.com",
                        query = "What is this website about?"
                    )
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            
            val response = testJson.decodeFromString<WebScrapeResponse>(bodyAsText())
            assertNotNull(response.response)
            assertTrue(response.response.isNotBlank())
        }
    }
    
    @Test
    fun `should return bad request for invalid URL`() = testApplication {
        application {
            module()
        }
        
        configureTestDependencies()
        
        client.post("/api/scrape") {
            contentType(ContentType.Application.Json)
            setBody(
                testJson.encodeToString(
                    WebScrapeRequest.serializer(),
                    WebScrapeRequest(
                        url = "invalid-url",
                        query = "What is this about?"
                    )
                )
            )
        }.apply {
            // This might return BadRequest or BadGateway depending on how the error is handled
            assertTrue(status.value >= 400)
        }
    }
    
    @Test
    fun `should return bad request for empty query`() = testApplication {
        application {
            module()
        }
        
        configureTestDependencies()
        
        client.post("/api/scrape") {
            contentType(ContentType.Application.Json)
            setBody(
                testJson.encodeToString(
                    WebScrapeRequest.serializer(),
                    WebScrapeRequest(
                        url = "https://example.com",
                        query = ""
                    )
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }
    
    private fun TestApplicationBuilder.configureTestDependencies() {
        application {
            // Override the default DI modules with test modules
            val testModule = module {
                single<WebScrapeService> { WebScrapeService() }
            }
            
            // Configure test dependencies
            environment {
                config = applicationEngineEnvironment {
                    module {
                        configureSerialization()
                        configureWebSockets()
                        configureRequestValidation()
                        configureWebScrapeRoutes()
                        
                        // Install test Koin module
                        install(org.koin.ktor.plugin.Koin) {
                            slf4jLogger()
                            modules(testModule)
                        }
                    }
                }
            }
        }
    }
} 