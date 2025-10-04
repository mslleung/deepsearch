package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.application.services.INavigationElementRemovalService
import io.deepsearch.application.services.IPopupDismissService
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Agentic search that iteratively extracts answers from the current page and
 * proposes actions to surface more relevant information if needed.
 */
class AgenticBrowserSearchStrategy(
    private val browserPool: IBrowserPool,
    private val webpageExtractionService: IWebpageExtractionService,
    private val popupDismissService: IPopupDismissService,
    private val navigationElementRemovalService: INavigationElementRemovalService,
) : IAgenticBrowserSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val url = searchQuery.url
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            // Dismiss any popups/cookie banners before extraction
            popupDismissService.dismissAll(page)

            // Remove header and footer navigation elements
            navigationElementRemovalService.removeNavigationElements(page)

            val title = page.getTitle()
            val description = page.getDescription()

            val extractedWebpageText = webpageExtractionService.extractWebpage(page)

            val webpageText = buildString {
                appendLine("URL: $url")
                appendLine("Title: $title")
                if (!description.isNullOrBlank()) {
                    appendLine("Description: $description")
                }
                appendLine(extractedWebpageText)
            }.trim()

            return SearchResult(
                originalQuery = searchQuery,
                content = webpageText,
                sources = listOf(url)
            )
        } finally {
            browser.close()
        }
    }
}