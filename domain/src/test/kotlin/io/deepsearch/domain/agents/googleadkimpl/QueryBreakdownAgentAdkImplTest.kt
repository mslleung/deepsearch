package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IQueryBreakdownAgent
import io.deepsearch.domain.agents.QueryBreakdownAgentInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryBreakdownAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IQueryBreakdownAgent>()

    @Test
    fun `simple query returns minimal breakdown points`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("What is the main content of this webpage?", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryBreakdownAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertTrue(output.breakdownPoints.isNotEmpty(), "Should return at least one requirement")
        assertTrue(output.breakdownPoints.size <= 3, "Simple query should have minimal requirements")
    }

    @Test
    fun `breakdown into 2 requirements`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("Find leadership info and headcount for the company", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryBreakdownAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertEquals(2, output.breakdownPoints.size, "Should break down into 2 atomic requirements")
        assertTrue(output.breakdownPoints.all { it.isNotBlank() }, "All requirements should be non-blank")
    }

    @Test
    fun `breakdown into 3 requirements`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery =
            SearchQuery("Find pricing, enterprise plan limits, and SLA details", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryBreakdownAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertEquals(3, output.breakdownPoints.size, "Should break down into 3 atomic requirements")
        assertTrue(output.breakdownPoints.all { it.isNotBlank() }, "All requirements should be non-blank")
    }

    @Test
    fun `overly broad query returns comprehensive but minimal requirements`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("Show me all products on your ecommerce website", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryBreakdownAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertTrue(output.breakdownPoints.isNotEmpty(), "Should return requirements even for broad query")
        assertTrue(output.breakdownPoints.size <= 5, "Should keep requirements minimal even for broad query")
    }

    @Test
    fun `natural query returns atomic requirements`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery("What is on sale?", "https://www.egltours.com/")

        // When
        val output = agent.generate(
            QueryBreakdownAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertTrue(output.breakdownPoints.isNotEmpty(), "Should return requirements")
        assertTrue(output.breakdownPoints.size <= 3, "Natural query should have minimal requirements")
    }

    @Test
    fun `complex query returns comprehensive breakdown`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            "Tell me about your company history, leadership team, product offerings, and pricing plans",
            "https://www.egltours.com/"
        )

        // When
        val output = agent.generate(
            QueryBreakdownAgentInput(
                searchQuery = searchQuery
            )
        )

        // Then
        assertTrue(output.breakdownPoints.size >= 4, "Complex query should break down into multiple requirements")
        assertTrue(output.breakdownPoints.all { it.isNotBlank() }, "All requirements should be non-blank")
    }
}

