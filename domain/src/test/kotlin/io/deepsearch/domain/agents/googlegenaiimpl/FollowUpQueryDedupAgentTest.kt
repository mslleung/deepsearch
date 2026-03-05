package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IFollowUpQueryDedupAgent
import io.deepsearch.domain.agents.FollowUpQueryDedupInput
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowUpQueryDedupAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IFollowUpQueryDedupAgent>()

    @Test
    fun `filters out semantically similar queries`() = runTest(testCoroutineDispatcher) {
        // Given - queries that are semantically the same
        val input = FollowUpQueryDedupInput(
            candidateQueries = listOf(
                "stripe pricing plans",
                "pricing for stripe",
                "what are stripe's pricing options"
            ),
            previouslySearchedQueries = emptyList(),
            originalQuery = "how much does stripe cost"
        )

        // When
        val output = agent.generate(input)

        // Then - should dedupe to at most 1 query (since they're all similar)
        assertTrue(
            output.dedupedQueries.size <= 1,
            "Should dedupe similar queries, got: ${output.dedupedQueries}"
        )
    }

    @Test
    fun `keeps distinct queries`() = runTest(testCoroutineDispatcher) {
        // Given - queries about different topics
        val input = FollowUpQueryDedupInput(
            candidateQueries = listOf(
                "stripe pricing",
                "stripe API documentation",
                "stripe webhooks setup"
            ),
            previouslySearchedQueries = emptyList(),
            originalQuery = "stripe overview"
        )

        // When
        val output = agent.generate(input)

        // Then - should keep all distinct queries
        assertTrue(
            output.dedupedQueries.size >= 2,
            "Should keep distinct queries, got: ${output.dedupedQueries}"
        )
    }

    @Test
    fun `filters queries similar to previously searched`() = runTest(testCoroutineDispatcher) {
        // Given - candidate query similar to one already searched
        val input = FollowUpQueryDedupInput(
            candidateQueries = listOf(
                "stripe subscription pricing"
            ),
            previouslySearchedQueries = listOf("stripe pricing plans"),
            originalQuery = "stripe costs"
        )

        // When
        val output = agent.generate(input)

        // Then - should filter out since it's similar to previously searched
        assertTrue(
            output.dedupedQueries.isEmpty(),
            "Should filter query similar to previously searched, got: ${output.dedupedQueries}"
        )
    }

    @Test
    fun `handles empty candidate queries`() = runTest(testCoroutineDispatcher) {
        // Given
        val input = FollowUpQueryDedupInput(
            candidateQueries = emptyList(),
            previouslySearchedQueries = listOf("original query"),
            originalQuery = "original query"
        )

        // When
        val output = agent.generate(input)

        // Then
        assertEquals(emptyList(), output.dedupedQueries, "Should return empty list for empty input")
    }

    @Test
    fun `filters queries similar to original query`() = runTest(testCoroutineDispatcher) {
        // Given - candidate that's essentially the same as original
        val input = FollowUpQueryDedupInput(
            candidateQueries = listOf(
                "what is stripe pricing",
                "stripe integration guide"
            ),
            previouslySearchedQueries = emptyList(),
            originalQuery = "stripe pricing information"
        )

        // When
        val output = agent.generate(input)

        // Then - should filter out the pricing query but keep integration guide
        val containsPricing = output.dedupedQueries.any { it.contains("pricing", ignoreCase = true) }
        val containsIntegration = output.dedupedQueries.any { it.contains("integration", ignoreCase = true) }
        
        assertTrue(
            containsIntegration,
            "Should keep distinct integration query, got: ${output.dedupedQueries}"
        )
    }
}

