package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.valueobjects.SourceWithRelevance
import kotlinx.coroutines.CoroutineDispatcher
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

    @Test
    fun `aggregate one single search result`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val result = SearchResult(
            originalQuery = originalQuery,
            answer = "The website https://www.example.com is a special domain name reserved for documentation and example purposes.",
            content = "The website https://www.example.com is a special domain name reserved for documentation and example purposes.",
            answerSources = listOf(SourceWithRelevance("https://www.example.com/", 1.0f)),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(result)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.answerSources.isNotEmpty() || aggregated.exploredSources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @Test
    fun `aggregate two search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val result1 = SearchResult(
            originalQuery = originalQuery,
            answer = "Example Domain is an illustrative domain reserved for use in documentation and examples.",
            content = "Example Domain is an illustrative domain reserved for use in documentation and examples.",
            answerSources = listOf(SourceWithRelevance("https://www.example.com/", 1.0f)),
            exploredSources = emptyList()
        )
        val result2 = SearchResult(
            originalQuery = originalQuery,
            answer = "The site explains that example.com is reserved and provides a simple sample page.",
            content = "The site explains that example.com is reserved and provides a simple sample page.",
            answerSources = listOf(SourceWithRelevance("https://www.iana.org/domains/example", 1.0f)),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(result1, result2)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.answerSources.isNotEmpty() || aggregated.exploredSources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @Test
    fun `irrelevant search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val irrelevant1 = SearchResult(
            originalQuery = originalQuery,
            answer = "Cats are popular pets known for their independence and agility.",
            content = "Cats are popular pets known for their independence and agility.",
            answerSources = listOf(SourceWithRelevance("https://www.cats.com/", 1.0f)),
            exploredSources = emptyList()
        )
        val irrelevant2 = SearchResult(
            originalQuery = originalQuery,
            answer = "The capital of France is Paris, a major European city.",
            content = "The capital of France is Paris, a major European city.",
            answerSources = listOf(SourceWithRelevance("https://en.wikipedia.org/wiki/Paris", 1.0f)),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(irrelevant1, irrelevant2)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.answerSources.isNotEmpty() || aggregated.exploredSources.isNotEmpty(), "Aggregated sources should include sources from input results")
    }

    @Test
    fun `mix of relevant and irrelevant search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val relevant = SearchResult(
            originalQuery = originalQuery,
            answer = "Example Domain is reserved for documentation and examples.",
            content = "Example Domain is reserved for documentation and examples.",
            answerSources = listOf(SourceWithRelevance("https://www.example.com/", 1.0f)),
            exploredSources = emptyList()
        )
        val irrelevant = SearchResult(
            originalQuery = originalQuery,
            answer = "Cats purr and are often kept as indoor pets.",
            content = "Cats purr and are often kept as indoor pets.",
            answerSources = listOf(SourceWithRelevance("https://www.cats.com/", 1.0f)),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(relevant, irrelevant)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        val allUrls = aggregated.answerSources.map { it.url } + aggregated.exploredSources
        assertTrue(allUrls.contains("https://www.example.com/"), "Aggregated sources should include at least one relevant source")
    }
}


