package io.deepsearch.application.services

import io.deepsearch.domain.agents.GoogleSearchLinkDiscoveryInput
import io.deepsearch.domain.agents.IGoogleSearchLinkDiscoveryAgent
import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IWebpageLinkDiscoveryService {
    suspend fun discoverRelevantLinks(
        searchQuery: SearchQuery,
        cleanedHtml: String
    ): List<WebpageLink>
}

class WebpageLinkDiscoveryService(
    private val googleSearchLinkDiscoveryAgent: IGoogleSearchLinkDiscoveryAgent,
    private val linkRelevanceAnalysisAgent: ILinkRelevanceAnalysisAgent
) : IWebpageLinkDiscoveryService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Discovers relevant links using parallel Google search and on-page link analysis.
     * Results are merged and deduplicated by URL.
     */
    override suspend fun discoverRelevantLinks(
        searchQuery: SearchQuery,
        cleanedHtml: String
    ): List<WebpageLink> = coroutineScope {
        logger.debug("Discovering links for query: '{}' on {}", searchQuery.query, searchQuery.url)

        // Run both discovery methods in parallel for speed
        val googleSearchDeferred = async {
            try {
                googleSearchLinkDiscoveryAgent.generate(
                    GoogleSearchLinkDiscoveryInput(searchQuery)
                ).links
            } catch (e: Exception) {
                logger.warn("Google search link discovery failed: {}", e.message)
                emptyList()
            }
        }

        val linkRelevanceDeferred = async {
            try {
                linkRelevanceAnalysisAgent.generate(
                    LinkRelevanceAnalysisInput(
                        html = cleanedHtml,
                        query = searchQuery.query
                    )
                ).links
            } catch (e: Exception) {
                logger.warn("Link relevance analysis failed: {}", e.message)
                emptyList()
            }
        }

        val googleLinks = googleSearchDeferred.await()
        val relevanceLinks = linkRelevanceDeferred.await()

        // Merge and deduplicate by URL, keeping first occurrence
        val allLinks = (googleLinks + relevanceLinks)
            .distinctBy { it.url }

        logger.debug("Discovered {} unique links ({} from Google, {} from relevance analysis)",
            allLinks.size, googleLinks.size, relevanceLinks.size)

        allLinks
    }
}

