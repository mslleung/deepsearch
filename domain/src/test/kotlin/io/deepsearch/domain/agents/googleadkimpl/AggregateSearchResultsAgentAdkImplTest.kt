package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregateSearchResultsAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IAggregateSearchResultsAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `aggregate one single search result`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val result = SearchResult(
            originalQuery = originalQuery,
            content = "The website https://www.example.com is a special domain name reserved for documentation and example purposes.",
            sources = listOf("https://www.example.com/")
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(result)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `aggregate two search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val result1 = SearchResult(
            originalQuery = originalQuery,
            content = "Example Domain is an illustrative domain reserved for use in documentation and examples.",
            sources = listOf("https://www.example.com/")
        )
        val result2 = SearchResult(
            originalQuery = originalQuery,
            content = "The site explains that example.com is reserved and provides a simple sample page.",
            sources = listOf("https://www.iana.org/domains/example")
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(result1, result2)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `irrelevant search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val irrelevant1 = SearchResult(
            originalQuery = originalQuery,
            content = "Cats are popular pets known for their independence and agility.",
            sources = listOf("https://www.cats.com/")
        )
        val irrelevant2 = SearchResult(
            originalQuery = originalQuery,
            content = "The capital of France is Paris, a major European city.",
            sources = listOf("https://en.wikipedia.org/wiki/Paris")
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(irrelevant1, irrelevant2)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.isEmpty(), "Aggregated sources should not be empty")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `mix of relevant and irrelevant search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val relevant = SearchResult(
            originalQuery = originalQuery,
            content = "Example Domain is reserved for documentation and examples.",
            sources = listOf("https://www.example.com/")
        )
        val irrelevant = SearchResult(
            originalQuery = originalQuery,
            content = "Cats purr and are often kept as indoor pets.",
            sources = listOf("https://www.cats.com/")
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(relevant, irrelevant)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.contains("https://www.example.com/"), "Aggregated sources should include at least one relevant source")
    }
}


