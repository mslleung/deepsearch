package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IGoogleUrlContextSearchAgent
import io.deepsearch.domain.agents.IQueryExpansionAgent
import io.deepsearch.domain.agents.QueryExpansionAgentInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryExpansionAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IQueryExpansionAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `url-context search returns content and at least one source`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("What is the main content of this webpage?", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryExpansionAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertEquals(output.expandedQueries.size, 1, "Direct query should not need expansion")
    }

    @Test
    fun `breakdown into 2 requests`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("Find leadership info and headcount for the company", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryExpansionAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertEquals(output.expandedQueries.size, 2, "Should expand into 2")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `breakdown into 3 requests`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery =
            SearchQuery("Find pricing, enterprise plan limits, and SLA details", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryExpansionAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertEquals(output.expandedQueries.size, 3, "Should expand into 3")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `overly board query`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("Show me all products on your ecommerce website", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryExpansionAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertEquals(output.expandedQueries.size, 1, "Should replace board query with simpler query")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `natural query`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("What is on sale?", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryExpansionAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertTrue(output.expandedQueries.size <= 2, "natural query should expand minimally")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `non retrieval queries`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("am I handsome?", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryExpansionAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertTrue(
            output.expandedQueries.isEmpty(),
            "not a query that can be answered by searching the company website"
        )
    }
}