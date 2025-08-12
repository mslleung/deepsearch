package io.deepsearch.domain.searchstrategies

import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult

interface IAgenticBrowserSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

class AgenticBrowserSearchStrategy : IAgenticBrowserSearchStrategy {

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        // Placeholder for Playwright-driven agentic search
        // TODO: Implement agentic crawling/search leveraging BrowserService and a dedicated agent
        TODO("Not yet implemented")
    }

}