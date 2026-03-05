package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.AnswerReviewerInput
import io.deepsearch.domain.agents.IAnswerReviewerAgent
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnswerReviewerAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koin = IsolatedKoinExtension.create { modules(domainTestModule) }

    private val agent by inject<IAnswerReviewerAgent>()
    private val testDispatcher by inject<CoroutineDispatcher>()

    @Test
    fun `should mark as complete when answer fully addresses simple query`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "What is the capital of France?",
            currentAnswer = "The capital of France is Paris."
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.isComplete, "Answer should be marked as complete for simple query with complete answer")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as incomplete when answer is insufficient for query`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "Explain the complete history, architecture, cultural significance, visitor statistics, and construction details of the Eiffel Tower",
            currentAnswer = "The Eiffel Tower is in Paris."
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.isComplete, "Answer should be marked as incomplete when lacking substantial information")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as incomplete when answer is blank`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "What is machine learning?",
            currentAnswer = ""
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.isComplete, "Should be incomplete when answer is blank")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as complete for invalid query`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "Good morning!",
            currentAnswer = "This doesn't seem to be a valid search query."
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.isComplete, "Should mark as complete for invalid/greeting queries")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as complete for gibberish query`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "*f&dbst4$",
            currentAnswer = "Unable to determine a meaningful answer for this query."
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.isComplete, "Should mark as complete for gibberish queries")
        assertNotNull(output.reason)
    }

    @Test
    fun `should be conservative for complex multi-part query with partial answer`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "Provide a comprehensive analysis of machine learning algorithms, their applications, limitations, and future trends",
            currentAnswer = "Machine learning is a subset of AI. Common algorithms include decision trees, neural networks, and support vector machines."
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // Should be incomplete - the query asks for comprehensive coverage including applications, limitations, and future trends
        assertFalse(output.isComplete, "Agent should be conservative and not mark incomplete answers as complete")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as complete when multi-part query is fully answered`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "What is the capital of France and what is it famous for?",
            currentAnswer = """
                The capital of France is Paris.
                
                Paris is famous for several landmarks and cultural attractions:
                - The Eiffel Tower, a wrought iron lattice tower built in 1889
                - The Louvre Museum, home to the Mona Lisa and thousands of artworks
                - Notre-Dame Cathedral, a medieval Catholic cathedral
                - Arc de Triomphe, a monument honoring those who fought for France
                
                Paris is also renowned for its cuisine, fashion, art, and culture.
            """.trimIndent()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.isComplete, "Should mark as complete when all parts of the query are addressed")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as incomplete for partially answered multi-part query`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "What is the capital of Germany and what is its population?",
            currentAnswer = "The capital of Germany is Berlin."
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.isComplete, "Should mark as incomplete when only one part of multi-part query is answered")
        assertNotNull(output.reason)
    }

    @Test
    fun `should mark as complete for factual query with precise answer`() = runTest(testDispatcher) {
        val input = AnswerReviewerInput(
            query = "What is 2+2?",
            currentAnswer = "2 + 2 = 4"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.isComplete, "Should mark as complete for simple factual query with correct answer")
        assertNotNull(output.reason)
    }
}

