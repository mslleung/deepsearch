package io.deepsearch.application.searchorchestrators.agenticbrowsersearch

import io.deepsearch.application.services.IKgHybridRetrievalService
import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.application.services.WebpageCacheService
import io.deepsearch.domain.models.valueobjects.LinkSource
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import io.deepsearch.domain.services.IGeminiFileSearchService
import io.deepsearch.domain.services.INormalizeUrlService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Facade service for all link discovery mechanisms in agentic search.
 * Consolidates SERP, Hybrid Search, Knowledge Graph, and File Search discovery.
 */
interface ILinkDiscoveryFacadeService {
    /**
     * Discovers links for a query using all available mechanisms in parallel.
     * Returns a flow of DiscoveredLink that emits as each mechanism completes.
     * 
     * @param query The search query to discover links for
     * @param baseSearchQuery The original search query context (for URL prefix, etc.)
     * @param sessionId The session ID for tracking
     * @param onLinkDiscovered Callback for each discovered link (for priority buffer)
     * @return Flow that completes when all discovery mechanisms finish
     */
    fun discoverLinksForQuery(
        query: String,
        baseSearchQuery: SearchQuery,
        sessionId: QuerySessionId,
        onLinkDiscovered: suspend (DiscoveredLink) -> Unit
    ): Flow<Unit>

    /**
     * Discovers links via SERP search only.
     * @param searchQuery The search query
     * @param sessionId The session ID for tracking
     * @return Flow of discovered WebpageLinks
     */
    fun discoverLinksBySerp(
        searchQuery: SearchQuery,
        sessionId: QuerySessionId
    ): Flow<WebpageLink>

}

class LinkDiscoveryFacadeService(
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
    private val webpageCacheService: WebpageCacheService,
    private val kgHybridRetrievalService: IKgHybridRetrievalService,
    private val geminiFileSearchService: IGeminiFileSearchService,
    private val normalizeUrlService: INormalizeUrlService,
    private val tokenUsageService: ILlmTokenUsageService
) : ILinkDiscoveryFacadeService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        // Default score for links from sources that don't provide scoring
        private const val DEFAULT_LINK_SCORE = 10
    }

    override fun discoverLinksForQuery(
        query: String,
        baseSearchQuery: SearchQuery,
        sessionId: QuerySessionId,
        onLinkDiscovered: suspend (DiscoveredLink) -> Unit
    ): Flow<Unit> {
        // Create a SearchQuery with this query but same URL context
        val currentSearchQuery = SearchQuery(
            rawQuery = query,
            url = baseSearchQuery.url,
            languagePattern = baseSearchQuery.languagePattern,
            ocrLanguage = baseSearchQuery.ocrLanguage
        )

        // Run all discovery mechanisms in parallel using flow composition
        return merge(
            // 1. SERP Search
            flow {
                try {
                    val serpLinks = webpageLinkDiscoveryService.discoverRelevantLinksBySerper(currentSearchQuery, sessionId)
                    logger.debug("[{}] SERP discovery for '{}': {} links", sessionId.value, query, serpLinks.size)
                    serpLinks.forEach { onLinkDiscovered(DiscoveredLink(it, query)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("[{}] SERP discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                }
                emit(Unit)
            },

            // 2. Hybrid Search (from cache) - discover URLs from cached pages
            flow {
                try {
                    val hybridResults = webpageCacheService.searchHybrid(
                        query, baseSearchQuery.url, null, 15, sessionId
                    )
                    val links = hybridResults.map { webpage ->
                        DiscoveredLink(
                            link = WebpageLink(
                                url = webpage.url,
                                source = LinkSource.HYBRID_SEARCH,
                                reason = "Hybrid search result for: $query",
                                score = DEFAULT_LINK_SCORE
                            ),
                            query = query
                        )
                    }
                    logger.debug("[{}] Hybrid discovery for '{}': {} links", sessionId.value, query, links.size)
                    links.forEach { onLinkDiscovered(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("[{}] Hybrid discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                }
                emit(Unit)
            },

            // 3. Knowledge Graph - discover URLs from KG entities
            flow {
                try {
                    val urlPrefix = normalizeUrlService.normalize(baseSearchQuery.url) ?: baseSearchQuery.url
                    if (kgHybridRetrievalService.hasDataForUrlPrefix(urlPrefix)) {
                        val kgResult = kgHybridRetrievalService.retrieve(
                            query = query,
                            baseUrl = urlPrefix,
                            maxCacheAge = null,
                            sessionId = sessionId
                        )
                        val links = kgResult.subgraph?.entities?.flatMap { entity ->
                            entity.sourceUrls.map { url ->
                                DiscoveredLink(
                                    link = WebpageLink(
                                        url = url,
                                        source = LinkSource.KNOWLEDGE_GRAPH,
                                        reason = "KG entity: ${entity.name}",
                                        score = DEFAULT_LINK_SCORE
                                    ),
                                    query = query
                                )
                            }
                        } ?: emptyList()
                        logger.debug("[{}] KG discovery for '{}': {} links", sessionId.value, query, links.size)
                        links.forEach { onLinkDiscovered(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("[{}] KG discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                }
                emit(Unit)
            },

            // 4. File Search - discover URLs from file search chunks
            flow {
                try {
                    val domain = extractDomain(baseSearchQuery.url)
                    val storeInfo = geminiFileSearchService.findStore(domain)
                    if (storeInfo != null) {
                        val searchResult = geminiFileSearchService.queryStore(
                            storeName = storeInfo.name,
                            query = query,
                            maxAgeMs = null
                        )
                        tokenUsageService.recordTokenUsage(
                            sessionId, "LinkDiscoveryFacadeService.FileSearch",
                            searchResult.tokenUsage.modelName, searchResult.tokenUsage.promptTokens,
                            searchResult.tokenUsage.outputTokens, searchResult.tokenUsage.totalTokens
                        )
                        val links = searchResult.chunks
                            .filter { it.sourceUrl.isNotBlank() }
                            .map { chunk ->
                                DiscoveredLink(
                                    link = WebpageLink(
                                        url = chunk.sourceUrl,
                                        source = LinkSource.FILE_SEARCH,
                                        reason = "File search chunk for: $query",
                                        score = DEFAULT_LINK_SCORE
                                    ),
                                    query = query
                                )
                            }.distinctBy { it.url }
                        logger.debug("[{}] File search discovery for '{}': {} links", sessionId.value, query, links.size)
                        links.forEach { onLinkDiscovered(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("[{}] File search discovery for query '{}' failed: {}", sessionId.value, query, e.message)
                }
                emit(Unit)
            }
        )
    }

    override fun discoverLinksBySerp(
        searchQuery: SearchQuery,
        sessionId: QuerySessionId
    ): Flow<WebpageLink> = flow {
        try {
            webpageLinkDiscoveryService.discoverRelevantLinksBySerper(searchQuery, sessionId).forEach { emit(it) }
        } catch (e: Exception) {
            logger.error("[{}] SERP search failed: {}", sessionId.value, e.message)
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.lowercase() ?: url
        } catch (e: Exception) {
            url
        }
    }
}
