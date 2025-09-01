package io.deepsearch.domain.searchstrategies.agenticbrowsersearch

import io.deepsearch.domain.agents.IWebpageActionExtractor
import io.deepsearch.domain.agents.IWebpageExtractAnswerAgent
import io.deepsearch.domain.agents.WebAction
import io.deepsearch.domain.agents.tools.WebActionExecutor
import io.deepsearch.domain.browser.IBrowserFactory
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
    private val browserFactory: IBrowserFactory,
    private val extractAnswerAgent: IWebpageExtractAnswerAgent,
    private val actionExtractor: IWebpageActionExtractor,
    private val actionExecutor: WebActionExecutor,
) : IAgenticBrowserSearchStrategy {

    private val confidenceThreshold: Double = 0.7
    private val maxSteps: Int = 8

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        val url = searchQuery.url
        val browser = browserFactory.createBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            val visited = mutableSetOf<String>()
            val answers = mutableListOf<IWebpageExtractAnswerAgent.Output.Found>()
            val performed = mutableListOf<WebAction>()

            var step = 0
            while (step < maxSteps) {
                step++
                val screenshot = page.takeScreenshot()
                val pageInfo = page.getPageInformation()
                val fingerprint = fingerprint(pageInfo)
                if (!visited.add(fingerprint)) break

                when (val out = extractAnswerAgent.generate(
                    IWebpageExtractAnswerAgent.Input(
                        searchQuery = searchQuery,
                        pageInformation = pageInfo,
                        screenshotBytes = screenshot.bytes
                    )
                )) {
                    is IWebpageExtractAnswerAgent.Output.Found -> {
                        answers.add(out)
                        if (out.confidence >= confidenceThreshold) break
                    }
                    is IWebpageExtractAnswerAgent.Output.NotFound -> {
                        // continue to action proposals
                    }
                }

                val proposals = actionExtractor.generate(
                    IWebpageActionExtractor.Input(
                        searchQuery = searchQuery,
                        pageInformation = pageInfo,
                        screenshotBytes = screenshot.bytes,
                        priorActions = performed
                    )
                )

                if (proposals.stopRecommended || proposals.proposals.isEmpty()) break

                var applied = false
                for (p in proposals.proposals) {
                    val result = actionExecutor.execute(page, p.action)
                    if (result.success) {
                        applied = true
                        performed.add(p.action)
                        page.waitForNavigationOrIdle()
                        break
                    }
                }
                if (!applied) break
            }

            return aggregate(answers, searchQuery, pageInfoOrUrl(page))
        } finally {
            browser.close()
        }
    }

    private fun fingerprint(pi: io.deepsearch.domain.browser.IBrowserPage.PageInformation): String {
        val mainHash = (pi.mainText ?: "").take(512).hashCode()
        return "${pi.url}::${pi.title}::$mainHash"
    }

    private fun pageInfoOrUrl(page: io.deepsearch.domain.browser.IBrowserPage): String {
        return try { page.getPageInformation().url } catch (_: Throwable) { "" }
    }

    private fun aggregate(
        answers: List<IWebpageExtractAnswerAgent.Output.Found>,
        query: SearchQuery,
        currentUrl: String
    ): SearchResult {
        if (answers.isEmpty()) {
            return SearchResult(
                originalQuery = query,
                content = "No relevant information found.",
                sources = listOfNotNull(currentUrl.ifBlank { null })
            )
        }
        val top = answers.maxByOrNull { it.confidence }!!
        return top.searchResult
    }
}