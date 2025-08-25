package io.deepsearch.domain.searchstrategies.agenticbrowsersearch

import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.browser.IBrowserFactory
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.searchstrategies.ISearchStrategy

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Searches a website the same way human does. It follows human thought process in performing the search.
 *
 * While traditional techniques uses HTML parsing and other mechanical methods for processing websites,
 * we have observed that ultimately webpages are visual in nature. It is therefore much more efficient to
 * mimic human search behaviour in modeling search strategy.
 */
class AgenticBrowserSearchStrategy(
    private val browserFactory: IBrowserFactory,
    private val blinkTestAgent: IBlinkTestAgent,
    private val visualAnalysisAgent: io.deepsearch.domain.agents.IVisualAnalysisAgent
) : IAgenticBrowserSearchStrategy {

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        /**
         * Using a browser and LLM agents, we mimic human search behaviour.
         *
         * 1. A blink test
         * Taking a screenshot of the current page. We make a snap judgement of whether the webpage is relevant and
         * related to our query. The result of the blink test is to conclude whether:
         *   - The page is completely irrelevant to our query. Conclude the search
         *   - The page is relevant to our search query, continue to explore current page
         *   - The screenshot does not provide enough information to decide yet. Will continue to explore current page.
         *
         * 2. Quick orientation
         * Quickly observe the navigation elements and decide on the next actions:
         * - Explore current page (invoke actions/query current visible information)
         * - Navigate (hover, click) to a different page.
         *
         * 3. Start all actions in parallel
         *    We will need to implement all actions in detail, perhaps breaking down to even smaller steps
         */
        val (query, url) = searchQuery
        val browser = browserFactory.createBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            // 1) Blink test from screenshot
            val screenshot = page.takeScreenshot()
            val blink = blinkTestAgent.generate(
                IBlinkTestAgent.BlinkTestInput(
                    searchQuery = searchQuery,
                    screenshotBytes = screenshot
                )
            )

            return when (blink.decision) {
                IBlinkTestAgent.Decision.IRRELEVANT -> {
                    // Conclude early
                    SearchResult(
                        originalQuery = searchQuery,
                        content = "No relevant information found on the current page for the query.",
                        sources = listOf(url)
                    )
                }

                IBlinkTestAgent.Decision.RELEVANT -> {
                    // 2) Visual analysis to answer from the current view
                    val visual = visualAnalysisAgent.generate(
                        io.deepsearch.domain.agents.IVisualAnalysisAgent.VisualAnalysisInput(
                            searchQuery = searchQuery,
                            screenshotBytes = screenshot
                        )
                    )
                    visual.searchResult
                }
            }
        } finally {
            browser.close()
        }
    }

}