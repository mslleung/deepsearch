package io.deepsearch.application.services

import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryInput
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.ITextLinkDiscoveryAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.agents.TextLinkDiscoveryInput
import io.deepsearch.domain.models.entities.SitemapCache
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.text.PDFTextStripper
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.repositories.ISitemapCacheRepository
import io.deepsearch.domain.services.INormalizeUrlService
import io.deepsearch.domain.services.ISerperService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface IWebpageLinkDiscoveryService {
    /**
     * Discovers relevant links using Google search
     */
    suspend fun discoverRelevantLinksByGoogleSearch(searchQuery: SearchQuery, sessionId: SessionId): List<WebpageLink>

    /**
     * Discovers relevant links using SERP search (serper.dev API)
     * @param searchQuery The search query
     * @param sessionId The session ID for tracking API usage (optional for backward compatibility)
     */
    suspend fun discoverRelevantLinksBySerper(searchQuery: SearchQuery, sessionId: SessionId? = null): List<WebpageLink>

    /**
     * Discovers relevant links by analyzing links on the current webpage.
     * @param excludeUrls absolute URLs to exclude from analysis (already visited/queued)
     */
    suspend fun discoverRelevantLinksByAgent(query: String, html: String, url: String, sessionId: SessionId, excludeUrls: Set<String> = emptySet()): List<WebpageLink>

    /**
     * Discovers all links on the page regardless of relevance.
     */
    suspend fun discoverAllLinks(html: String, url: String): List<WebpageLink>

    /**
     * Discovers links from a sitemap XML file. Uses caching with 1-day TTL.
     */
    suspend fun discoverSitemapLinks(sitemapUrl: String): List<WebpageLink>

    /**
     * Discovers links from plain text content (e.g., file search results, markdown).
     * Extracts URLs using regex and filters to same-domain.
     */
    fun discoverLinksFromText(text: String, sourceUrl: String): List<WebpageLink>

    /**
     * Discovers relevant links from text content using LLM analysis.
     * Similar to discoverRelevantLinksByAgent but for plain text instead of HTML.
     */
    suspend fun discoverRelevantLinksFromText(query: String, text: String, sourceUrl: String, sessionId: SessionId): List<WebpageLink>

    /**
     * Discovers all links from a PDF file without using LLM.
     * Uses PDFBox to extract URLs from PDF annotations and text content.
     */
    fun discoverAllLinksFromPdf(pdfBytes: ByteArray, sourceUrl: String): List<WebpageLink>
}

@OptIn(ExperimentalTime::class)
class WebpageLinkDiscoveryService(
    private val googleSearchLinkDiscoveryAgent: IGoogleSearchLinkDiscoveryAgent,
    private val serperService: ISerperService,
    private val linkRelevanceAnalysisAgent: ILinkRelevanceAnalysisAgent,
    private val textLinkDiscoveryAgent: ITextLinkDiscoveryAgent,
    private val sitemapCacheRepository: ISitemapCacheRepository,
    private val sitemapLockRegistry: ISitemapLinkDiscoveryLockRegistry,
    private val normalizeUrlService: INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService,
    private val externalApiUsageService: IExternalApiUsageService
) : IWebpageLinkDiscoveryService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val client = HttpClient(OkHttp) {
        followRedirects = true
        expectSuccess = false
    }

    /**
     * Extracts the base domain from a URL (scheme + host) and normalizes it.
     * For example: "https://example.com/path" -> "https://example.com"
     * 
     * Uses regex to extract the scheme and host, which handles URLs with
     * illegal characters (like unencoded spaces in query parameters).
     * The extracted base domain is then normalized to ensure consistent comparison.
     */
    private fun extractBaseDomain(url: String): String? {
        val regex = Regex("^(https?://[^/]+)")
        val baseDomain = regex.find(url)?.value ?: return null
        // Normalize the base domain to ensure consistent comparison
        return normalizeUrlService.normalize(baseDomain) ?: baseDomain
    }

    override suspend fun discoverRelevantLinksByGoogleSearch(searchQuery: SearchQuery, sessionId: SessionId): List<WebpageLink> {
        logger.debug("Discovering links via Google search for query: '{}' on {}", searchQuery.query, searchQuery.url)

        val output = googleSearchLinkDiscoveryAgent.generate(
            GoogleSearchLinkDiscoveryInput(searchQuery)
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "GoogleSearchLinkDiscoveryAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )

        logger.debug("Discovered {} links from Google search", output.links.size)
        return output.links
    }

    override suspend fun discoverRelevantLinksBySerper(searchQuery: SearchQuery, sessionId: SessionId?): List<WebpageLink> {
        logger.debug("Discovering links via SERP search for query: '{}' on {}", searchQuery.query, searchQuery.url)

        val links = serperService.searchLinks(searchQuery)

        // Record external API usage if session ID is provided
        if (sessionId != null) {
            externalApiUsageService.recordSerperSearch(sessionId, searchQuery.query)
        }

        logger.debug("Discovered {} links from SERP search", links.size)
        return links
    }

    override suspend fun discoverRelevantLinksByAgent(query: String, html: String, url: String, sessionId: SessionId, excludeUrls: Set<String>): List<WebpageLink> {
        logger.debug("Discovering links via on-page analysis for query: '{}' (excluding {} already-visited URLs)", query, excludeUrls.size)

        val baseDomain = extractBaseDomain(url)
        if (baseDomain == null) {
            logger.warn("Could not extract base domain from URL: {}", url)
            return emptyList()
        }

        val output = linkRelevanceAnalysisAgent.generate(
            LinkRelevanceAnalysisInput(
                html = html,
                query = query,
                url = url,
                excludeUrls = excludeUrls
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "LinkRelevanceAnalysisAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )

        val filteredLinks = output.links.filter { link ->
            val normalizedLinkUrl = normalizeUrlService.normalize(link.url) ?: link.url
            val normalizedLinkBaseDomain = extractBaseDomain(normalizedLinkUrl)
            normalizedLinkBaseDomain == baseDomain
        }
        logger.debug("Discovered {} links from on-page analysis (filtered to {} same-domain links)", output.links.size, filteredLinks.size)
        return filteredLinks
    }

    override suspend fun discoverAllLinks(html: String, url: String): List<WebpageLink> {
        val baseDomain = extractBaseDomain(url)
        if (baseDomain == null) {
            logger.warn("Could not extract base domain from URL: {}", url)
            return emptyList()
        }

        val doc = Jsoup.parse(html, url)  // Pass base URL for resolution
        val anchors = doc.select("a[href]")
        val links = anchors.mapNotNull { a ->
            val href = a.absUrl("href").trim()  // Use absUrl instead of attr
            if (href.isBlank()) null else WebpageLink(
                url = href,
                source = LinkSource.ALL_LINKS,
                reason = url
            )
        }
        
        val filteredLinks = links.filter { link ->
            val normalizedLinkUrl = normalizeUrlService.normalize(link.url) ?: link.url
            val normalizedLinkBaseDomain = extractBaseDomain(normalizedLinkUrl)
            normalizedLinkBaseDomain == baseDomain
        }
        logger.debug("Discovered {} links total (filtered to {} same-domain links)", links.size, filteredLinks.size)
        return filteredLinks
    }

    override suspend fun discoverSitemapLinks(sitemapUrl: String): List<WebpageLink> {
        logger.debug("Discovering links from sitemap: {}", sitemapUrl)

        // Use lock registry to deduplicate concurrent requests for same sitemap
        return sitemapLockRegistry.withKeyLock(sitemapUrl) {
            // Check cache first
            val cachedSitemap = sitemapCacheRepository.findByUrl(sitemapUrl)
            if (cachedSitemap != null) {
                if (!cachedSitemap.isExpired()) {
                    logger.debug(
                        "Returning cached sitemap links for {}: {} links",
                        sitemapUrl,
                        cachedSitemap.links.size
                    )
                    return@withKeyLock cachedSitemap.links.map { url ->
                        WebpageLink(
                            url = url,
                            source = io.deepsearch.domain.models.valueobjects.LinkSource.SITEMAP,
                            reason = "From sitemap (cached): $sitemapUrl"
                        )
                    }
                }
            }

            // Fetch sitemap via HTTP
            logger.debug("Fetching sitemap from {}", sitemapUrl)
            val xmlContent = fetchSitemapXml(sitemapUrl)

            // Parse XML to extract URLs
            val urls = parseSitemapXml(xmlContent).distinct()

            logger.debug("Extracted {} URLs from sitemap {}", urls.size, sitemapUrl)

            // Convert to WebpageLinks
            val links = urls.map { url ->
                WebpageLink(
                    url = url,
                    source = LinkSource.SITEMAP,
                    reason = "From sitemap: $sitemapUrl"
                )
            }

            // Save to cache
            val sitemapCache = SitemapCache(
                sitemapUrl = sitemapUrl,
                xmlContent = xmlContent,
                links = urls,
                createdAt = cachedSitemap?.createdAt ?: Clock.System.now(),
                updatedAt = Clock.System.now()
            )
            sitemapCacheRepository.upsert(sitemapCache)

            logger.debug("Cached {} links from sitemap {}", links.size, sitemapUrl)
            links
        }
    }

    private suspend fun fetchSitemapXml(sitemapUrl: String): String {
        val response = client.get(sitemapUrl)
        if (response.status.value !in 200..299) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
        }
        return response.bodyAsText()
    }

    private fun parseSitemapXml(xmlContent: String): List<String> {
        val documentBuilder = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
        val doc = documentBuilder.parse(xmlContent.byteInputStream())
        val locElements = doc.getElementsByTagName("loc")

        return (0 until locElements.length).mapNotNull { i ->
            val textContent = locElements.item(i)?.textContent?.trim()
            if (textContent.isNullOrBlank()) null else textContent
        }
    }

    override fun discoverLinksFromText(text: String, sourceUrl: String): List<WebpageLink> {
        val baseDomain = extractBaseDomain(sourceUrl)
        if (baseDomain == null) {
            logger.warn("Could not extract base domain from URL: {}", sourceUrl)
            return emptyList()
        }

        // Extract URLs using regex
        val urlPattern = """https?://[^\s<>"{}|\\^`\[\]]+""".toRegex()
        val urls = urlPattern.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', ';', ':', '\'', '"') }
            .filter { url ->
                try {
                    val normalizedUrl = normalizeUrlService.normalize(url) ?: url
                    val urlBaseDomain = extractBaseDomain(normalizedUrl)
                    urlBaseDomain == baseDomain
                } catch (e: Exception) {
                    false
                }
            }
            .distinct()
            .toList()

        val links = urls.map { url ->
            WebpageLink(
                url = url,
                source = LinkSource.FILE_CONTENT,
                reason = "URL found in file search result"
            )
        }

        logger.debug("Discovered {} same-domain links from text content", links.size)
        return links
    }

    override suspend fun discoverRelevantLinksFromText(
        query: String,
        text: String,
        sourceUrl: String,
        sessionId: SessionId
    ): List<WebpageLink> {
        logger.debug("Discovering relevant links from text for query: '{}'", query)

        val baseDomain = extractBaseDomain(sourceUrl)
        if (baseDomain == null) {
            logger.warn("Could not extract base domain from URL: {}", sourceUrl)
            return emptyList()
        }

        val output = textLinkDiscoveryAgent.generate(
            TextLinkDiscoveryInput(
                text = text,
                sourceUrl = sourceUrl,
                query = query
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "TextLinkDiscoveryAgent",
            modelName = output.tokenUsage.modelName,
            promptTokens = output.tokenUsage.promptTokens,
            outputTokens = output.tokenUsage.outputTokens,
            totalTokens = output.tokenUsage.totalTokens
        )

        val filteredLinks = output.links.filter { link ->
            val normalizedLinkUrl = normalizeUrlService.normalize(link.url) ?: link.url
            val normalizedLinkBaseDomain = extractBaseDomain(normalizedLinkUrl)
            normalizedLinkBaseDomain == baseDomain
        }

        logger.debug("Discovered {} relevant links from text (filtered to {} same-domain)", output.links.size, filteredLinks.size)
        return filteredLinks
    }

    override fun discoverAllLinksFromPdf(pdfBytes: ByteArray, sourceUrl: String): List<WebpageLink> {
        val baseDomain = extractBaseDomain(sourceUrl)
        if (baseDomain == null) {
            logger.warn("Could not extract base domain from URL: {}", sourceUrl)
            return emptyList()
        }

        val urls = mutableSetOf<String>()

        try {
            val document = Loader.loadPDF(pdfBytes)
            document.use { pdf ->
                // 1. Extract URLs from PDF link annotations
                for (page in pdf.pages) {
                    val annotations = page.annotations ?: continue
                    for (annotation in annotations) {
                        if (annotation is PDAnnotationLink) {
                            val action = annotation.action
                            if (action is PDActionURI) {
                                val uri = action.uri
                                if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                                    urls.add(uri)
                                }
                            }
                        }
                    }
                }

                // 2. Extract URLs from PDF text content using regex
                val stripper = PDFTextStripper()
                val text = stripper.getText(pdf)
                val urlPattern = """https?://[^\s<>"{}|\\^`\[\]]+""".toRegex()
                urlPattern.findAll(text).forEach { match ->
                    val url = match.value.trimEnd('.', ',', ')', ']', ';', ':', '\'', '"')
                    urls.add(url)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract links from PDF: {}", e.message)
            return emptyList()
        }

        // Filter to same-domain URLs
        val filteredUrls = urls.filter { url ->
            try {
                val normalizedUrl = normalizeUrlService.normalize(url) ?: url
                val urlBaseDomain = extractBaseDomain(normalizedUrl)
                urlBaseDomain == baseDomain
            } catch (e: Exception) {
                false
            }
        }

        val links = filteredUrls.map { url ->
            WebpageLink(
                url = url,
                source = LinkSource.FILE_CONTENT,
                reason = "URL found in PDF"
            )
        }

        logger.debug("Discovered {} same-domain links from PDF", links.size)
        return links
    }
}

