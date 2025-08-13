package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AggregateSearchResultsServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val aggregateSearchResultsService by inject<IAggregateSearchResultsService>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `aggregate one single search result`() = runTest {
        // Given
        val originalQuery = SearchQuery("Tell me about this website", "https://www.example.com/")
        val result = SearchResult(
            originalQuery = originalQuery,
            content = "The website https://www.example.com is a special domain name reserved for documentation and example purposes.",
            sources = listOf("https://www.example.com/")
        )

        // When
        val aggregated = aggregateSearchResultsService.aggregate(originalQuery, listOf(result))

        // Then
        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `aggregate two search results`() = runTest {
        // Given
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

        // When
        val aggregated = aggregateSearchResultsService.aggregate(originalQuery, listOf(result1, result2))

        // Then
        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.isNotEmpty(), "Aggregated sources should not be empty")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `irrelevant search results`() = runTest {
        // Given
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

        // When
        val aggregated = aggregateSearchResultsService.aggregate(originalQuery, listOf(irrelevant1, irrelevant2))

        // Then
        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.isEmpty(), "Aggregated sources should not be empty")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `mix of relevant and irrelevant search results`() = runTest {
        // Given
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

        // When
        val aggregated = aggregateSearchResultsService.aggregate(originalQuery, listOf(relevant, irrelevant))

        // Then
        assertEquals(originalQuery, aggregated.originalQuery)
        assertTrue(aggregated.content.isNotBlank(), "Aggregated content should not be blank")
        assertTrue(aggregated.sources.contains("https://www.example.com/"), "Aggregated sources should include at least one relevant source")
    }
}