package io.deepsearch.application.searchstrategies.agenticbrowsersearch

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.application.searchstrategies.ISearchStrategy
import io.deepsearch.application.services.IWebpageExtractionService
import io.deepsearch.application.services.IPopupDismissService
import io.deepsearch.application.services.WebpageExtractionService
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.repositories.IWebpageIconRepository
import io.deepsearch.domain.models.entities.WebpageIcon
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

            val title = page.getTitle()
            val description = page.getDescription()

            val extractedWebpageText = webpageExtractionService.extractWebpage(page)

            // v1 content: basic page summary
            val summary = buildString {
                appendLine("Title: $title")
                if (!description.isNullOrBlank()) {
                    appendLine("Description: $description")
                }
                appendLine(extractedWebpageText)
            }.trim()

            return SearchResult(
                originalQuery = searchQuery,
                content = summary,
                sources = listOf(url)
            )
        } finally {
            browser.close()
        }
    }

//    private data class WebpageAnalysisResults(
//        val url: String,
//        val answer: String?,
////        val actions: List<IAgenticBrowserAction>
//    )
//
//    private suspend fun analyzeWebpage(query: String, url: String): WebpageAnalysisResults {
//        // TODO use agents to analyze the webpage and return the results
//        // 1. try to extract an answer from the current page using an llm agent
//        // 2. look for additional actions that may yield more information
//        // 3. synthesize the WebpageAnalysisResults and return
//        TODO()
//    }
}