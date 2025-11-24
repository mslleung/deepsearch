package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.AggregateSearchResultsInput
import io.deepsearch.domain.agents.IAggregateSearchResultsAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregateSearchResultsAgentTest : KoinTest {

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
            contentSources = listOf(MarkdownSource("https://www.example.com/", null, null, "The website https://www.example.com is a special domain name reserved for documentation and example purposes.")),
            answerSources = listOf("https://www.example.com/"),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(result)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.contentSources.isNotEmpty(), "Aggregated content sources should not be empty")
        assertTrue(aggregated.answerSources.isNotEmpty() || aggregated.exploredSources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @Test
    fun `aggregate two search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val result1 = SearchResult(
            originalQuery = originalQuery,
            answer = "Example Domain is an illustrative domain reserved for use in documentation and examples.",
            contentSources = listOf(MarkdownSource("https://www.example.com/", null, null, "Example Domain is an illustrative domain reserved for use in documentation and examples.")),
            answerSources = listOf("https://www.example.com/"),
            exploredSources = emptyList()
        )
        val result2 = SearchResult(
            originalQuery = originalQuery,
            answer = "The site explains that example.com is reserved and provides a simple sample page.",
            contentSources = listOf(MarkdownSource("https://www.iana.org/domains/example", null, null, "The site explains that example.com is reserved and provides a simple sample page.")),
            answerSources = listOf("https://www.iana.org/domains/example"),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(result1, result2)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.contentSources.isNotEmpty(), "Aggregated content sources should not be empty")
        assertTrue(aggregated.answerSources.isNotEmpty() || aggregated.exploredSources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @Test
    fun `irrelevant search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val irrelevant1 = SearchResult(
            originalQuery = originalQuery,
            answer = "Cats are popular pets known for their independence and agility.",
            contentSources = listOf(MarkdownSource("https://www.cats.com/", null, null, "Cats are popular pets known for their independence and agility.")),
            answerSources = listOf("https://www.cats.com/"),
            exploredSources = emptyList()
        )
        val irrelevant2 = SearchResult(
            originalQuery = originalQuery,
            answer = "The capital of France is Paris, a major European city.",
            contentSources = listOf(MarkdownSource("https://en.wikipedia.org/wiki/Paris", null, null, "The capital of France is Paris, a major European city.")),
            answerSources = listOf("https://en.wikipedia.org/wiki/Paris"),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(irrelevant1, irrelevant2)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.contentSources.isNotEmpty(), "Aggregated content sources should not be empty")
        assertTrue(aggregated.answerSources.isNotEmpty() || aggregated.exploredSources.isNotEmpty(), "Aggregated sources should include sources from input results")
    }

    @Test
    fun `mix of relevant and irrelevant search results`() = runTest(testCoroutineDispatcher) {
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val relevant = SearchResult(
            originalQuery = originalQuery,
            answer = "Example Domain is reserved for documentation and examples.",
            contentSources = listOf(MarkdownSource("https://www.example.com/", null, null, "Example Domain is reserved for documentation and examples.")),
            answerSources = listOf("https://www.example.com/"),
            exploredSources = emptyList()
        )
        val irrelevant = SearchResult(
            originalQuery = originalQuery,
            answer = "Cats purr and are often kept as indoor pets.",
            contentSources = listOf(MarkdownSource("https://www.cats.com/", null, null, "Cats purr and are often kept as indoor pets.")),
            answerSources = listOf("https://www.cats.com/"),
            exploredSources = emptyList()
        )

        val output = agent.generate(AggregateSearchResultsInput(originalQuery, listOf(relevant, irrelevant)))
        val aggregated = output.aggregatedResult

        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.answer.isNotBlank(), "Aggregated answer should not be blank")
        assertTrue(aggregated.contentSources.isNotEmpty(), "Aggregated content sources should not be empty")
        val allUrls = aggregated.answerSources + aggregated.exploredSources
        assertTrue(allUrls.contains("https://www.example.com/"), "Aggregated sources should include at least one relevant source")
    }
}


