package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.IWebpageLinkDiscoveryService
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.WebpageLink
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Agentic search that iteratively discovers and extracts relevant pages.
 * Uses link discovery (Google search + on-page analysis) to traverse the website
 * and extracts content from each relevant page found.
 */
class AgenticBrowserSearchStrategy(
    private val browserPool: IBrowserPool,
    private val webpageExtractionService: IWebpageExtractionService,
    private val webpageLinkDiscoveryService: IWebpageLinkDiscoveryService,
) : IAgenticBrowserSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery): SearchResult = coroutineScope {
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()

            // Track visited URLs to prevent cycles and duplicates
            val visitedUrls = mutableSetOf<String>()
            val extractedContent = mutableListOf<String>()

            // Start with the initial URL
            val urlsToVisit = mutableListOf(searchQuery.url)

            while (urlsToVisit.isNotEmpty()) {
                val currentUrl = urlsToVisit.removeAt(0)

                // Skip if already visited
                if (currentUrl in visitedUrls) {
                    continue
                }

                logger.debug("Visiting URL: {}", currentUrl)
                visitedUrls.add(currentUrl)

                try {
                    // Navigate to the page
                    page.navigate(currentUrl)

                    // Extract webpage content (with caching)
                    val extractedText = webpageExtractionService.extractWebpage(page)
                    extractedContent.add(extractedText)

                    // Discover relevant links
                    val discoveredLinks = webpageLinkDiscoveryService.discoverRelevantLinks(
                        searchQuery = searchQuery,
                        webpage = page
                    )

                    // Add new links to visit queue (filter out already visited)
                    val newLinks = discoveredLinks
                        .map { it.url }
                        .filter { it !in visitedUrls && it !in urlsToVisit }

                    logger.debug("Discovered {} new links to visit from {}", newLinks.size, currentUrl)
                    urlsToVisit.addAll(newLinks)

                } catch (e: Exception) {
                    logger.warn("Failed to process URL {}: {}", currentUrl, e.message)
                    // Continue with other URLs even if one fails
                }
            }

            // Aggregate all extracted content
            val aggregatedContent = extractedContent.joinToString("\n\n---\n\n")

            logger.debug("Search completed. Visited {} pages, extracted {} chars",
                visitedUrls.size, aggregatedContent.length)

            SearchResult(
                originalQuery = searchQuery,
                content = aggregatedContent,
                sources = visitedUrls.toList()
            )

        } finally {
            browser.close()
        }
    }
}