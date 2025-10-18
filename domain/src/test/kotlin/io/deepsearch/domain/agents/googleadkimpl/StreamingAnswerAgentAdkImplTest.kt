package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamingAnswerAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val agent by inject<IStreamingAnswerAgent>()

    @Test
    fun `should generate initial answer from first batch`() = runTest {
        val input = StreamingAnswerInput(
            query = "What is the capital of France?",
            currentAnswer = null,
            markdownBatch = listOf(
                "# France\nFrance is a country in Western Europe. Its capital city is Paris, which is known for the Eiffel Tower."
            )
        )

        val output = agent.generate(input)

        assertNotNull(output.updatedAnswer)
        assertTrue(output.updatedAnswer.isNotBlank(), "Answer should not be blank")
        assertContains(output.updatedAnswer.lowercase(), "paris", ignoreCase = true)
    }

    @Test
    fun `should update answer with new information from second batch`() = runTest {
        // First batch
        val firstInput = StreamingAnswerInput(
            query = "Tell me about the Eiffel Tower",
            currentAnswer = null,
            markdownBatch = listOf(
                "# Eiffel Tower\nThe Eiffel Tower is a wrought iron lattice tower located in Paris, France."
            )
        )

        val firstOutput = agent.generate(firstInput)

        // Second batch with additional info
        val secondInput = StreamingAnswerInput(
            query = "Tell me about the Eiffel Tower",
            currentAnswer = firstOutput.updatedAnswer,
            markdownBatch = listOf(
                "The Eiffel Tower was completed in 1889 and stands 330 meters tall. It was designed by Gustave Eiffel."
            )
        )

        val secondOutput = agent.generate(secondInput)

        assertNotNull(secondOutput.updatedAnswer)
        assertTrue(secondOutput.updatedAnswer.length > firstOutput.updatedAnswer.length,
            "Second answer should be more comprehensive")
        assertContains(secondOutput.updatedAnswer.lowercase(), "1889", ignoreCase = true)
        assertContains(secondOutput.updatedAnswer.lowercase(), "gustave eiffel", ignoreCase = true)
    }

    @Test
    fun `should mark answer as complete when sufficient information is provided`() = runTest {
        val input = StreamingAnswerInput(
            query = "What is 2+2?",
            currentAnswer = null,
            markdownBatch = listOf(
                "# Basic Math\nThe answer to 2 plus 2 is 4. This is a fundamental arithmetic operation."
            )
        )

        val output = agent.generate(input)

        assertNotNull(output.updatedAnswer)
        assertContains(output.updatedAnswer, "4")
        assertTrue(output.isComplete, "Answer should be marked as complete for simple query with clear answer")
    }

    @Test
    fun `should not mark answer as complete when information is insufficient`() = runTest {
        val input = StreamingAnswerInput(
            query = "Explain the complete history, architecture, cultural significance, visitor statistics, and construction details of the Eiffel Tower",
            currentAnswer = null,
            markdownBatch = listOf(
                "The Eiffel Tower is in Paris."
            )
        )

        val output = agent.generate(input)

        assertNotNull(output.updatedAnswer)
        assertFalse(output.isComplete, "Answer should not be complete with minimal information for complex query")
    }

    @Test
    fun `should preserve existing answer when new batch has no relevant information`() = runTest {
        // First batch with relevant info
        val firstInput = StreamingAnswerInput(
            query = "What is the capital of Germany?",
            currentAnswer = null,
            markdownBatch = listOf(
                "Germany is a country in Europe. Its capital is Berlin, which is also its largest city."
            )
        )

        val firstOutput = agent.generate(firstInput)

        // Second batch with irrelevant info
        val secondInput = StreamingAnswerInput(
            query = "What is the capital of Germany?",
            currentAnswer = firstOutput.updatedAnswer,
            markdownBatch = listOf(
                "France is known for its cuisine and wine production.",
                "The Great Wall of China is one of the world's most famous landmarks."
            )
        )

        val secondOutput = agent.generate(secondInput)

        assertNotNull(secondOutput.updatedAnswer)
        assertContains(secondOutput.updatedAnswer.lowercase(), "berlin", ignoreCase = true)
        // Answer should be similar in length since no new relevant info was added
        assertTrue(
            kotlin.math.abs(secondOutput.updatedAnswer.length - firstOutput.updatedAnswer.length) < 100,
            "Answer should not change significantly when no relevant info is provided"
        )
    }

    @Test
    fun `should handle empty markdown batch gracefully`() = runTest {
        val input = StreamingAnswerInput(
            query = "What is AI?",
            currentAnswer = "AI stands for Artificial Intelligence.",
            markdownBatch = listOf("")
        )

        val output = agent.generate(input)

        assertNotNull(output.updatedAnswer)
        assertTrue(output.updatedAnswer.isNotBlank())
    }

    @Test
    fun `should be conservative about marking completeness`() = runTest {
        val input = StreamingAnswerInput(
            query = "Provide a comprehensive analysis of machine learning algorithms, their applications, limitations, and future trends",
            currentAnswer = null,
            markdownBatch = listOf(
                "Machine learning is a subset of AI. Common algorithms include decision trees, neural networks, and support vector machines."
            )
        )

        val output = agent.generate(input)

        assertNotNull(output.updatedAnswer)
        // Should not be complete - the query asks for comprehensive coverage including applications, limitations, and future trends
        assertFalse(output.isComplete, "Agent should be conservative and not mark incomplete answers as complete")
    }
}

