package io.deepsearch.domain.searchstrategies.agenticbrowsersearch

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.searchstrategies.ISearchStrategy

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Agentic search that iteratively extracts answers from the current page and
 * proposes actions to surface more relevant information if needed.
 */
class AgenticBrowserSearchStrategy(
    private val browserPool: IBrowserPool,
) : IAgenticBrowserSearchStrategy {

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val url = searchQuery.url
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)


            TODO()
        } finally {
            browser.close()
        }
    }

    private data class WebpageAnalysisResults(
        val url: String,
        val answer: String?,
        val actions: List<IAgenticBrowserAction>
    )

    private suspend fun analyzeWebpage(query: String, url: String): WebpageAnalysisResults {
        // TODO use agents to analyze the webpage and return the results
        // 1. try to extract an answer from the current page using an llm agent
        // 2. look for additional actions that may yield more information
        // 3. synthesize the WebpageAnalysisResults and return
        TODO()
    }
}