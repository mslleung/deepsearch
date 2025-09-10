package io.deepsearch.domain.services

import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.domain.searchstrategies.googlesearch.IGoogleSearchStrategy
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IUnifiedSearchService {
    suspend fun performSearch(searchQuery: SearchQuery): SearchResult
}

class UnifiedSearchService(
    private val aggregateSearchResultsAgent: IAggregateSearchResultsAgent,
    private val queryExpansionAgent: IQueryExpansionAgent,
    private val agenticBrowserSearchStrategy: IAgenticBrowserSearchStrategy,
    private val googleTextSearchStrategy: IGoogleSearchStrategy,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : IUnifiedSearchService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun performSearch(searchQuery: SearchQuery): SearchResult = withContext(dispatcher) {
        val (query, url) = searchQuery

        logger.debug("Start unified search: {} {}", query, url)
        val agentInput = io.deepsearch.domain.agents.QueryExpansionAgentInput(searchQuery = searchQuery)
        val agentOutput = queryExpansionAgent.generate(agentInput)
        val subqueries = agentOutput.expandedQueries

        // Process all subqueries in parallel
        val subquerySearchResults = subqueries.map { subquery ->
            async {
                searchSubquery(subquery)
            }
        }.awaitAll()

        // Aggregate all search results into a single final result
        aggregateResults(searchQuery, subquerySearchResults)
    }

    private suspend fun searchSubquery(searchQuery: SearchQuery): SearchResult = coroutineScope {
        val searchStrategies = listOf(
            agenticBrowserSearchStrategy,
            googleTextSearchStrategy
        )

        val searchResults = searchStrategies.map { searchStrategy ->
            async {
                searchStrategy.execute(searchQuery)
            }
        }.awaitAll()

        aggregateResults(searchQuery, searchResults)
    }

    private suspend fun aggregateResults(
        originalQuery: SearchQuery,
        searchResults: List<SearchResult>
    ): SearchResult {
        return aggregateSearchResultsAgent
            .generate(AggregateSearchResultsInput(originalQuery, searchResults))
            .aggregatedResult
    }
}