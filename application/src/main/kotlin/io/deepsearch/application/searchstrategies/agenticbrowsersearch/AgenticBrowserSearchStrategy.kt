package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.application.services.IQueryAnsweringService
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
    private val queryAnsweringService: IQueryAnsweringService,
) : IAgenticBrowserSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val url = searchQuery.url
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            // val webpageText = webpageExtractionService.extractWebpage(page)

            // Take screenshot and get HTML for query answering
            val screenshot = page.takeFullPageScreenshot()
            val html = page.getFullHtml()

            return queryAnsweringService.answerQuery(
                screenshotBytes = screenshot.bytes,
                html = html,
                searchQuery = searchQuery
            )
        } finally {
            browser.close()
        }
    }
}