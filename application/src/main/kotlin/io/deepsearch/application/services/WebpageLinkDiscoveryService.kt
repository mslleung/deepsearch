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
    /**
     * Discovers relevant links using Google search
     */
    suspend fun discoverRelevantLinksByGoogleSearch(searchQuery: SearchQuery): List<WebpageLink>

    /**
     * Discovers relevant links by analyzing links on the current webpage
     */
    suspend fun discoverRelevantLinksByAgent(query: String, html: String): List<WebpageLink>
}

class WebpageLinkDiscoveryService(
    private val googleSearchLinkDiscoveryAgent: IGoogleSearchLinkDiscoveryAgent,
    private val linkRelevanceAnalysisAgent: ILinkRelevanceAnalysisAgent
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
}

