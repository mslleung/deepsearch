package io.deepsearch.domain.searchstrategies.googlesearch

import io.deepsearch.domain.agents.IGoogleCombinedSearchAgent
import io.deepsearch.domain.agents.IGoogleTextSearchAgent
import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.searchstrategies.ISearchStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IGoogleSearchStrategy : ISearchStrategy {
    override suspend fun execute(searchQuery: SearchQuery): SearchResult
}

/**
 * Performs a Google search + Url Context, powered by Google Gemini.
 *
 * Google has a long history of being the best search engine in the world, so we will leverage it.
 * Use this as a benchmark.
 */
class GoogleSearchStrategy(
    // private val googleCombinedSearchAgent: IGoogleCombinedSearchAgent
    private val googleTextSearchAgent: IGoogleTextSearchAgent,
    private val googleUrlContextSearchAgent: IGoogleUrlContextSearchAgent
) : IGoogleSearchStrategy {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(searchQuery: SearchQuery): SearchResult {
        logger.debug("GoogleSearchStrategy.execute start: '{}' on {}", searchQuery.query, searchQuery.url)
        // Previous implementation using the combined search agent (not supported yet):
        // val output = googleCombinedSearchAgent.generate(
        //     IGoogleCombinedSearchAgent.GoogleCombinedSearchInput(searchQuery)
        // )
        // return output.searchResult

        // 1) Run text search to discover candidate sources
        val googleTextSearchOutput = googleTextSearchAgent.generate(
            IGoogleTextSearchAgent.GoogleTextSearchInput(searchQuery)
        )
        val textSources = googleTextSearchOutput.searchResult.sources
        logger.debug("Text search found {} sources; first: {}", textSources.size, textSources.firstOrNull())

        val baseUrl = searchQuery.url
        val candidateSources = googleTextSearchOutput.searchResult.sources
            .filter { it.startsWith(baseUrl) }

        logger.debug("Filtered to {} candidate sources starting with '{}'", candidateSources.size, baseUrl)

        // 2) Select up to the first 20 matching sources, or fall back to the base URL
        val selectedUrls: List<String> = when {
            candidateSources.isNotEmpty() -> candidateSources.take(20)
            else -> listOf(baseUrl)
        }

        logger.debug("Selected {} URL(s) for URL-context", selectedUrls.size)

        // 3) Run URL-context agent against the selected URL(s)
        val urlContextOutput = googleUrlContextSearchAgent.generate(
            IGoogleUrlContextSearchAgent.GoogleUrlContextSearchInput(
                query = searchQuery.query,
                urls = selectedUrls
            )
        )
        logger.debug(
            "URL-context content length: {}, sources: {}",
            urlContextOutput.content.length,
            urlContextOutput.sources
        )

        // 4) Return the results, preserving the original query and normalizing sources
        //    to the original URL to maintain existing expectations.
        return SearchResult(
            originalQuery = searchQuery,
            content = urlContextOutput.content,
            sources = urlContextOutput.sources
        )
    }
}