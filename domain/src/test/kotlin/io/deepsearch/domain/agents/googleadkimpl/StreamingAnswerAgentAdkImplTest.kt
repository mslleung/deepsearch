package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.agents.StreamingAnswerOutput
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
    fun `should generate answer when sufficient information is provided`() = runTest {
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
    fun `should handle many sequential markdown batches and accumulate information`() = runTest {
        val query = "Tell me about Tokyo"
        
        // Simulate multiple batches arriving sequentially, each adding new information
        val batches = listOf(
            listOf("# Tokyo\nTokyo is the capital city of Japan."),
            listOf("Tokyo is located on the eastern coast of Honshu, Japan's main island."),
            listOf("The greater Tokyo area has a population of over 37 million people, making it the world's most populous metropolitan area."),
            listOf("Tokyo was originally known as Edo until 1868 when it became the imperial capital."),
            listOf("The city is known for its modern architecture including Tokyo Tower and Tokyo Skydome."),
            listOf("Tokyo hosted the Summer Olympics in 1964 and 2021."),
            listOf("The city is a major financial center and home to the Tokyo Stock Exchange."),
            listOf("Tokyo's public transportation system, including the famous Yamanote Line, is one of the most efficient in the world."),
            listOf("Famous districts include Shibuya, Shinjuku, Harajuku, and Akihabara."),
            listOf("Tokyo cuisine includes sushi, ramen, tempura, and many other traditional Japanese dishes.")
        )

        var currentAnswer: String? = null
        val outputHistory = mutableListOf<StreamingAnswerOutput>()

        // Process batches sequentially
        for ((index, batch) in batches.withIndex()) {
            val input = StreamingAnswerInput(
                query = query,
                currentAnswer = currentAnswer,
                markdownBatch = batch
            )
            
            val output = agent.generate(input)
            outputHistory.add(output)
            currentAnswer = output.updatedAnswer
            
            assertNotNull(output.updatedAnswer, "Batch $index should produce an answer")
            assertTrue(output.updatedAnswer.isNotBlank(), "Answer from batch $index should not be blank")
        }

        // Verify the answer accumulated information over time
        val finalAnswer = currentAnswer!!
        assertContains(finalAnswer.lowercase(), "tokyo", ignoreCase = true)
        assertContains(finalAnswer.lowercase(), "japan", ignoreCase = true)
        
        // Check that answer generally grew or maintained substance
        assertTrue(finalAnswer.length > 100, "Final answer should be comprehensive after many batches")
        
        // At least one of the later batches should have more content than the first
        assertTrue(
            outputHistory.takeLast(5).any { it.updatedAnswer.length > outputHistory.first().updatedAnswer.length },
            "Answer should accumulate information across batches"
        )
    }

    @Test
    fun `should handle large batch with parallel processing (more than 20 markdowns)`() = runTest {
        // Create a batch with more than 20 markdowns to trigger parallel processing
        val largeMarkdownBatch = (1..25).map { i ->
            """
            # Tokyo Section $i
            This is section $i about Tokyo. Tokyo is an amazing city with district number $i.
            District $i has unique characteristics including shopping area $i and restaurant zone $i.
            Historical site $i dates back to the Edo period.
            """.trimIndent()
        }

        val input = StreamingAnswerInput(
            query = "What are the districts and characteristics of Tokyo?",
            currentAnswer = null,
            markdownBatch = largeMarkdownBatch
        )

        val output = agent.generate(input)

        assertNotNull(output.updatedAnswer)
        assertTrue(output.updatedAnswer.isNotBlank(), "Answer should not be blank even with large batch")
        assertContains(output.updatedAnswer.lowercase(), "tokyo", ignoreCase = true)
        
        // The answer should synthesize information from the large batch
        assertTrue(
            output.updatedAnswer.length > 50,
            "Answer should be comprehensive after processing large batch"
        )
    }

    @Test
    fun `should progressively build comprehensive answer across many batches until complete`() = runTest {
        val query = "What is the capital of France and what is it famous for?"
        
        // First batch with partial info
        val firstInput = StreamingAnswerInput(
            query = query,
            currentAnswer = null,
            markdownBatch = listOf("France is a country in Western Europe.")
        )
        val firstOutput = agent.generate(firstInput)
        
        // Second batch with the capital
        val secondInput = StreamingAnswerInput(
            query = query,
            currentAnswer = firstOutput.updatedAnswer,
            markdownBatch = listOf("The capital of France is Paris.")
        )
        val secondOutput = agent.generate(secondInput)
        assertContains(secondOutput.updatedAnswer.lowercase(), "paris", ignoreCase = true)
        
        // Third batch with what it's famous for
        val thirdInput = StreamingAnswerInput(
            query = query,
            currentAnswer = secondOutput.updatedAnswer,
            markdownBatch = listOf("Paris is famous for the Eiffel Tower, a wrought iron lattice tower built in 1889.")
        )
        val thirdOutput = agent.generate(thirdInput)
        assertContains(thirdOutput.updatedAnswer.lowercase(), "eiffel tower", ignoreCase = true)
        
        // Fourth batch with more famous landmarks
        val fourthInput = StreamingAnswerInput(
            query = query,
            currentAnswer = thirdOutput.updatedAnswer,
            markdownBatch = listOf("Paris is also known for the Louvre Museum, Notre-Dame Cathedral, and Arc de Triomphe.")
        )
        val fourthOutput = agent.generate(fourthInput)
        
        // Final answer should contain all accumulated information
        assertContains(fourthOutput.updatedAnswer.lowercase(), "paris", ignoreCase = true)
        assertContains(fourthOutput.updatedAnswer.lowercase(), "eiffel", ignoreCase = true)
        assertTrue(
            fourthOutput.updatedAnswer.length > thirdOutput.updatedAnswer.length,
            "Answer should continue to grow with relevant information"
        )
        
        // The query was straightforward and we provided comprehensive info, 
        // so it might be marked complete
        assertTrue(
            fourthOutput.updatedAnswer.contains("Paris") && 
            fourthOutput.updatedAnswer.lowercase().contains("eiffel"),
            "Answer should contain key information from multiple batches"
        )
    }
}

