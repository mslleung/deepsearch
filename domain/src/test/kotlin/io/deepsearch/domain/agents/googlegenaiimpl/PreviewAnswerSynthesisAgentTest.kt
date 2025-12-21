package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IPreviewAnswerSynthesisAgent
import io.deepsearch.domain.agents.PreviewAnswerSynthesisInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewAnswerSynthesisAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPreviewAnswerSynthesisAgent>()

    @Test
    fun `should return default message when shortlist is empty`() = runTest(testCoroutineDispatcher) {
        val input = PreviewAnswerSynthesisInput(
            query = "What is the company founded year?",
            shortlistedSources = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.answerFound, "Should not find answer with empty shortlist")
        assertTrue(output.answer.isNotBlank(), "Should provide a default message")
    }

    @Test
    fun `should generate answer from high-confidence facts`() = runTest(testCoroutineDispatcher) {
        val source = PreviewShortlistedSource(
            url = "https://example.com/about",
            title = "About Us",
            extractedFacts = listOf(
                "Example Corp was founded in 2015",
                "The company is headquartered in San Francisco",
                "John Smith is the CEO"
            ),
            confidence = 0.95f,
            relevanceJustification = "Contains founding information in clear prose"
        )

        val input = PreviewAnswerSynthesisInput(
            query = "When was Example Corp founded and who is the CEO?",
            shortlistedSources = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Should generate an answer")
        // The agent should synthesize from the provided facts
        assertNotNull(output.tokenUsage)
    }

    @Test
    fun `should stream answer chunks correctly`() = runTest(testCoroutineDispatcher) {
        val source = PreviewShortlistedSource(
            url = "https://example.com/about",
            title = "About Us",
            extractedFacts = listOf("Example Corp was founded in 2015"),
            confidence = 0.95f,
            relevanceJustification = "Contains founding year"
        )

        val input = PreviewAnswerSynthesisInput(
            query = "When was Example Corp founded?",
            shortlistedSources = listOf(source)
        )

        val items = agent.generateStream(input).toList()

        assertTrue(items.isNotEmpty(), "Should emit at least one item")
        
        // Last item should be Complete
        val lastItem = items.last()
        assertTrue(
            lastItem is io.deepsearch.domain.agents.AnswerStreamItem.Complete,
            "Last item should be Complete"
        )
    }

    @Test
    fun `should indicate low confidence for partial information`() = runTest(testCoroutineDispatcher) {
        val source = PreviewShortlistedSource(
            url = "https://example.com/about",
            title = "About Us",
            extractedFacts = listOf("The company provides software solutions"),
            confidence = 0.7f, // Low confidence
            relevanceJustification = "Mentions company but not founding year"
        )

        val input = PreviewAnswerSynthesisInput(
            query = "When was the company founded?",
            shortlistedSources = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // With low confidence facts that don't answer the query,
        // the agent should indicate it can't confidently answer
        // This is expected behavior for the conservative preview path
        assertTrue(output.confidence < 0.9f || !output.answerFound,
            "Should have low confidence or not find answer for partial information")
    }
}
