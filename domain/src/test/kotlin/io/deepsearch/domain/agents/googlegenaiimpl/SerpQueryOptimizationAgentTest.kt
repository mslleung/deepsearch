package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ISerpQueryOptimizationAgent
import io.deepsearch.domain.agents.SerpQueryOptimizationInput
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class SerpQueryOptimizationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ISerpQueryOptimizationAgent>()

    @Test
    fun `optimizes verbose query about pricing`() = runTest(testCoroutineDispatcher) {
        // Given
        val input = SerpQueryOptimizationInput(
            query = "I want to find out what are all the different pricing plans and subscription options available for enterprise customers",
            targetUrl = "https://example.com"
        )

        // When
        val output = agent.generate(input)

        // Then
        assertTrue(output.optimizedQuery.isNotBlank(), "Should return non-blank optimized query")
        assertTrue(
            output.optimizedQuery.split("\\s+".toRegex()).size <= 10,
            "Optimized query should be concise (<=10 words)"
        )
        assertTrue(
            output.optimizedQuery.contains("pricing", ignoreCase = true) ||
                    output.optimizedQuery.contains("enterprise", ignoreCase = true),
            "Optimized query should preserve key terms"
        )
    }

    @Test
    fun `optimizes verbose query about API integration`() = runTest(testCoroutineDispatcher) {
        // Given
        val input = SerpQueryOptimizationInput(
            query = "Can you help me understand how the API authentication works and what credentials I need to get started with the integration",
            targetUrl = "https://example.com/docs"
        )

        // When
        val output = agent.generate(input)

        // Then
        assertTrue(output.optimizedQuery.isNotBlank(), "Should return non-blank optimized query")
        assertTrue(
            output.optimizedQuery.split("\\s+".toRegex()).size <= 10,
            "Optimized query should be concise (<=10 words)"
        )
        assertTrue(
            output.optimizedQuery.contains("API", ignoreCase = true) ||
                    output.optimizedQuery.contains("auth", ignoreCase = true),
            "Optimized query should preserve key API-related terms"
        )
    }

    @Test
    fun `optimizes long natural language query`() = runTest(testCoroutineDispatcher) {
        // Given
        val input = SerpQueryOptimizationInput(
            query = "I would really like to know more about the company leadership team including the CEO and other executives who are running the business",
            targetUrl = "https://example.com"
        )

        // When
        val output = agent.generate(input)

        // Then
        assertTrue(output.optimizedQuery.isNotBlank(), "Should return non-blank optimized query")
        val wordCount = output.optimizedQuery.split("\\s+".toRegex()).size
        assertTrue(wordCount <= 10, "Optimized query should be concise, got $wordCount words: ${output.optimizedQuery}")
    }
}

