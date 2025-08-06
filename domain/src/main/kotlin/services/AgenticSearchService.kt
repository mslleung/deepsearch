package io.deepsearch.domain.services

import agents.StrategyAgent
import com.google.adk.runner.InMemoryRunner
import io.deepsearch.domain.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import services.IBrowserService

interface IAgenticSearchService {
    suspend fun performSearch(searchQuery: SearchQuery): String
}

class AgenticSearchService(
    private val browserService: IBrowserService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : IAgenticSearchService {
    override suspend fun performSearch(searchQuery: SearchQuery): String = withContext(dispatcher) {
        browserService.createBrowser().use { browser ->

            val browserContext = browser.createContext()
            val page = browserContext.newPage()

            page.navigate(searchQuery.url)

//            page.takeFullPageScreenshot()  // TODO

            val runner = InMemoryRunner(StrategyAgent.ROOT_AGENT)

            "Search completed for query: ${searchQuery.query} on URL: ${searchQuery.url}"
        }
    }
}