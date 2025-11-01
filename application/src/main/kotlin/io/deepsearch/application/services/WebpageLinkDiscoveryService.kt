package io.deepsearch.application.services

import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryInput
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IWebpageLinkDiscoveryService {
    /**
     * Discovers relevant links using Google search
     */
    suspend fun discoverRelevantLinksByGoogleSearch(searchQuery: SearchQuery): List<WebpageLink>

    /**
     * Discovers relevant links by analyzing links on the current webpage
     */
    suspend fun discoverRelevantLinksByAgent(query: String, html: String): List<WebpageLink>

    /**
     * Discovers all links on the page regardless of relevance.
     */
    suspend fun discoverAllLinks(html: String, baseUrlForReason: String? = null): List<WebpageLink>

    /**
     * Discovers links from a sitemap XML file. Uses caching with 1-day TTL.
     */
    suspend fun discoverSitemapLinks(sitemapUrl: String): List<WebpageLink>
}

@OptIn(ExperimentalTime::class)
class WebpageLinkDiscoveryService(
    private val googleSearchLinkDiscoveryAgent: IGoogleSearchLinkDiscoveryAgent,
    private val linkRelevanceAnalysisAgent: ILinkRelevanceAnalysisAgent,
    private val sitemapCacheRepository: io.deepsearch.domain.repositories.ISitemapCacheRepository,
    private val sitemapLockRegistry: ISitemapLinkDiscoveryLockRegistry
) : IWebpageLinkDiscoveryService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun discoverRelevantLinksByGoogleSearch(searchQuery: SearchQuery): List<WebpageLink> {
        logger.debug("Discovering links via Google search for query: '{}' on {}", searchQuery.query, searchQuery.url)

        val links = googleSearchLinkDiscoveryAgent.generate(
            GoogleSearchLinkDiscoveryInput(searchQuery)
        ).links

        logger.debug("Discovered {} links from Google search", links.size)
        return links
    }

    override suspend fun discoverRelevantLinksByAgent(query: String, html: String): List<WebpageLink> {
        logger.debug("Discovering links via on-page analysis for query: '{}'", query)

        val links = linkRelevanceAnalysisAgent.generate(
            LinkRelevanceAnalysisInput(
                html = html,
                query = query
            )
        ).links

        logger.debug("Discovered {} links from on-page analysis", links.size)
        return links
    }

    override suspend fun discoverAllLinks(html: String, baseUrlForReason: String?): List<WebpageLink> {
        val doc = org.jsoup.Jsoup.parse(html)
        val anchors = doc.select("a[href]")
        val links = anchors.mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) null else WebpageLink(
                url = href,
                source = io.deepsearch.domain.models.valueobjects.LinkSource.ALL_LINKS,
                reason = baseUrlForReason ?: (a.text().takeIf { it.isNotBlank() } ?: "anchor")
            )
        }
        return links
    }

    override suspend fun discoverSitemapLinks(sitemapUrl: String): List<WebpageLink> {
        logger.debug("Discovering links from sitemap: {}", sitemapUrl)

        // Use lock registry to deduplicate concurrent requests for same sitemap
        return sitemapLockRegistry.withKeyLock(sitemapUrl) {
            // Check cache first
            val cachedSitemap = sitemapCacheRepository.findByUrl(sitemapUrl)
            if (cachedSitemap != null) {
                val cacheAgeMs = Clock.System.now().toEpochMilliseconds() -
                    cachedSitemap.updatedAt.toEpochMilliseconds()
                val oneDayMs = 24 * 60 * 60 * 1000L

                if (cacheAgeMs < oneDayMs) {
                    logger.debug("Returning cached sitemap links for {}: {} links", sitemapUrl, parseLinksFromJson(cachedSitemap.linksJson).size)
                    return@withKeyLock parseLinksFromJson(cachedSitemap.linksJson)
                }
            }

            // Fetch sitemap via HTTP
            logger.debug("Fetching sitemap from {}", sitemapUrl)
            val xmlContent = try {
                fetchSitemapXml(sitemapUrl)
            } catch (e: Exception) {
                logger.error("Failed to fetch sitemap from {}: {}", sitemapUrl, e.message, e)
                throw IllegalStateException("Failed to fetch sitemap from $sitemapUrl: ${e.message}", e)
            }

            // Parse XML to extract URLs
            val urls = try {
                parseSitemapXml(xmlContent)
            } catch (e: Exception) {
                logger.error("Failed to parse sitemap XML from {}: {}", sitemapUrl, e.message, e)
                throw IllegalStateException("Failed to parse sitemap XML from $sitemapUrl: ${e.message}", e)
            }

            logger.debug("Extracted {} URLs from sitemap {}", urls.size, sitemapUrl)

            // Convert to WebpageLinks
            val links = urls.map { url ->
                WebpageLink(
                    url = url,
                    source = io.deepsearch.domain.models.valueobjects.LinkSource.SITEMAP,
                    reason = "From sitemap: $sitemapUrl"
                )
            }

            // Save to cache
            val linksJson = serializeLinksToJson(urls)
            val sitemapCache = io.deepsearch.domain.models.entities.SitemapCache(
                sitemapUrl = sitemapUrl,
                xmlContent = xmlContent,
                linksJson = linksJson,
                createdAt = cachedSitemap?.createdAt ?: Clock.System.now(),
                updatedAt = Clock.System.now()
            )
            sitemapCacheRepository.upsert(sitemapCache)

            logger.debug("Cached {} links from sitemap {}", links.size, sitemapUrl)
            links
        }
    }

    private suspend fun fetchSitemapXml(sitemapUrl: String): String {
        val client = HttpClient(OkHttp) {
            followRedirects = true
            expectSuccess = false
        }

        try {
            val response = client.get(sitemapUrl)
            if (response.status.value !in 200..299) {
                throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
            }
            return response.bodyAsText()
        } finally {
            client.close()
        }
    }

    private fun parseSitemapXml(xmlContent: String): List<String> {
        val documentBuilder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
        val doc = documentBuilder.parse(xmlContent.byteInputStream())
        val locElements = doc.getElementsByTagName("loc")
        
        return (0 until locElements.length).mapNotNull { i ->
            val textContent = locElements.item(i)?.textContent?.trim()
            if (textContent.isNullOrBlank()) null else textContent
        }
    }

    private fun serializeLinksToJson(urls: List<String>): String {
        // Simple JSON array serialization
        return urls.joinToString(separator = ",", prefix = "[", postfix = "]") { url ->
            "\"${url.replace("\"", "\\\"")}\""
        }
    }

    private fun parseLinksFromJson(linksJson: String): List<WebpageLink> {
        // Simple JSON array parsing - remove brackets and quotes, split by comma
        val trimmed = linksJson.trim().removeSurrounding("[", "]")
        if (trimmed.isBlank()) return emptyList()
        
        val urls = trimmed.split("\",\"").map { url ->
            url.trim().removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
        }
        
        return urls.map { url ->
            WebpageLink(
                url = url,
                source = io.deepsearch.domain.models.valueobjects.LinkSource.SITEMAP,
                reason = "From sitemap (cached)"
            )
        }
    }
}

