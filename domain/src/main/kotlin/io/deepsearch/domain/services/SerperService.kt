package io.deepsearch.domain.services

import io.deepsearch.domain.config.SerperConfig
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service for interacting with serper.dev search API
 */
interface ISerperService {
    /**
     * Searches using serper.dev API and returns relevant links filtered by the target URL.
     *
     * @param searchQuery The search query containing the query text and target URL
     * @return List of WebpageLinks from organic search results
     */
    suspend fun searchLinks(searchQuery: SearchQuery): List<WebpageLink>
}

/**
 * Service for interacting with serper.dev search API.
 * This is a domain service that handles the technical operation of calling the SERP API.
 */
class SerperService(
    private val serperConfig: SerperConfig,
    private val normalizeUrlService: INormalizeUrlService
) : ISerperService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 5000
        }
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class SerperResponse(
        val organic: List<OrganicResult>? = null
    )

    @Serializable
    data class OrganicResult(
        val title: String? = null,
        val link: String? = null,
        val snippet: String? = null
    )

    override suspend fun searchLinks(searchQuery: SearchQuery): List<WebpageLink> {
        val (query, url) = searchQuery
        logger.debug("SERP search: '{}' on site {}", query, url)

        try {
            // Normalize the target URL
            val normalizedTargetUrl = normalizeUrlService.normalize(url)
            if (normalizedTargetUrl == null) {
                logger.error("Failed to normalize target URL: {}", url)
                return emptyList()
            }
            
            logger.debug("Normalized target URL: {}", normalizedTargetUrl)
            
            // Extract host and path from normalized URL for site: operator
            val uri = java.net.URI(normalizedTargetUrl)
            val hostWithPath = buildString {
                append(uri.host)
                if (uri.path.isNotEmpty() && uri.path != "/") {
                    append(uri.path)
                }
            }
            
            // Build search query with site: operator
            val searchQueryText = "$query site:$hostWithPath"
            logger.debug("Search query with site operator: {}", searchQueryText)
            
            val requestBody = """{"q":"$searchQueryText"}"""
            
            val response = client.post("https://google.serper.dev/search") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-API-KEY", serperConfig.apiKey)
                }
                setBody(requestBody)
            }
            
            if (response.status.value !in 200..299) {
                logger.error("SERP API returned error: ${response.status.value}")
                return emptyList()
            }

            val responseBody = response.bodyAsText()

            val serperResponse = json.decodeFromString<SerperResponse>(responseBody)
            val organicResults = serperResponse.organic ?: emptyList()

            logger.debug("SERP API returned {} organic results", organicResults.size)

            // Extract links, normalize them, and filter by normalized URL prefix
            val links = organicResults.mapNotNull { result ->
                val link = result.link ?: return@mapNotNull null
                
                // Normalize the returned link
                val normalizedLink = normalizeUrlService.normalize(link)
                if (normalizedLink == null) {
                    logger.debug("Failed to normalize returned link: {}", link)
                    return@mapNotNull null
                }
                
                // Filter by normalized URL prefix
                if (normalizedLink.startsWith(normalizedTargetUrl)) {
                    val reason = result.snippet ?: result.title ?: "Found via SERP search"
                    WebpageLink(
                        url = normalizedLink,
                        source = LinkSource.SERPER_SEARCH,
                        reason = reason
                    )
                } else {
                    null
                }
            }.distinctBy { it.url }

            logger.debug("SERP search found {} links", links.size)

            return links
        } catch (e: Exception) {
            logger.error("Error during SERP search: {}", e.message, e)
            return emptyList()
        }
    }
}

