package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class QueryExpansionServiceTest  : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val queryExpansionService: IQueryExpansionService by inject()

    @Test
    fun `simple direct query`() = runTest {
        // Given
        val query = "What is the main content of this webpage?"

        // When
        val expandedQuery = queryExpansionService.expandQuery(query)

        // Then
        assertTrue(expandedQuery.size == 1, "Direct query should not need expansion")
    }

    @Test
    fun `breakdown into 2 requests`() = runTest {
        // Given
        val query = "Find leadership info and headcount for the company"

        // When
        val expandedQuery = queryExpansionService.expandQuery(query)

        // Then
        assertTrue(expandedQuery.size == 2, "Should expand into 2")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `breakdown into 3 requests`() = runTest {
        // Given
        val query = "Find pricing, enterprise plan limits, and SLA details"

        // When
        val expandedQuery = queryExpansionService.expandQuery(query)

        // Then
        assertTrue(expandedQuery.size == 3, "Should expand into 3")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `overly board query`() = runTest {
        // Given
        val query = "Show me all products on your ecommerce website"

        // When
        val expandedQuery = queryExpansionService.expandQuery(query)

        // Then
        assertTrue(expandedQuery.size == 1, "Should replace board query with simpler query")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `natural query`() = runTest {
        // Given
        val query = "What is on sale?"

        // When
        val expandedQuery = queryExpansionService.expandQuery(query)

        // Then
        assertTrue(expandedQuery.size == 2, "natural query should expand minimally")
    }
}