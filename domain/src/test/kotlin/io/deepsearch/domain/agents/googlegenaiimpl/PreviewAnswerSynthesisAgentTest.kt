package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ClassifiedSource
import io.deepsearch.domain.agents.IPreviewAnswerSynthesisAgent
import io.deepsearch.domain.agents.PreviewAnswerStreamItem
import io.deepsearch.domain.agents.PreviewAnswerSynthesisInput
import io.deepsearch.domain.agents.RelevantFact
import io.deepsearch.domain.agents.SourceClassification
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
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
    fun `should return answerFound=false when no sources provided`() = runTest(testCoroutineDispatcher) {
        val input = PreviewAnswerSynthesisInput(
            query = "Who is the CEO?",
            sourceClassifications = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.answerFound, "answerFound should be false with no sources")
        assertTrue(output.reasoning.isNotBlank(), "Should have reasoning explaining why")
    }

    @Test
    fun `should return answerFound=false when all facts are in tables`() = runTest(testCoroutineDispatcher) {
        val source = ClassifiedSource(
            url = "https://example.com/pricing",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Pro plan costs $99/month",
                    isInTable = true,
                    classification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Enterprise plan costs $299/month",
                    isInTable = true,
                    classification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "How much does the Pro plan cost?",
            sourceClassifications = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // Should filter out table facts and find no valid facts
        assertFalse(output.answerFound, "answerFound should be false when all facts are in tables")
    }

    @Test
    fun `should return answerFound=false when all facts are from OFFICIAL_SNAPSHOT`() = runTest(testCoroutineDispatcher) {
        val source = ClassifiedSource(
            url = "https://example.com/blog/2023/announcement",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "We launched AI analytics in June 2023",
                    isInTable = false,
                    classification = SourceClassification.OFFICIAL_SNAPSHOT
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "Does the platform support AI analytics?",
            sourceClassifications = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // Should filter out OFFICIAL_SNAPSHOT facts and find no valid facts
        assertFalse(output.answerFound, "answerFound should be false when all facts are from snapshots")
    }

    @Test
    fun `should return answerFound=true when valid OFFICIAL_LIVING_DOC non-table facts exist`() = runTest(testCoroutineDispatcher) {
        val source = ClassifiedSource(
            url = "https://example.com/about",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Jane Doe is the CEO of Example Corp",
                    isInTable = false,
                    classification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Example Corp was founded in 2015",
                    isInTable = false,
                    classification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "Who is the CEO of Example Corp?",
            sourceClassifications = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answerFound, "answerFound should be true with valid facts")
        assertTrue(output.answer.contains("Jane", ignoreCase = true), "Answer should mention Jane Doe")
        assertTrue(output.reasoning.isNotBlank(), "Should have reasoning")
    }

    @Test
    fun `should filter facts and only use OFFICIAL_LIVING_DOC non-table facts`() = runTest(testCoroutineDispatcher) {
        val sources = listOf(
            ClassifiedSource(
                url = "https://example.com/about",
                relevantFacts = listOf(
                    RelevantFact(
                        fact = "Jane Doe is the CEO",
                        isInTable = false,
                        classification = SourceClassification.OFFICIAL_LIVING_DOC
                    )
                )
            ),
            ClassifiedSource(
                url = "https://example.com/blog/update",
                relevantFacts = listOf(
                    RelevantFact(
                        fact = "John Smith was appointed as interim CEO in 2022",
                        isInTable = false,
                        classification = SourceClassification.OFFICIAL_SNAPSHOT
                    )
                )
            ),
            ClassifiedSource(
                url = "https://example.com/pricing",
                relevantFacts = listOf(
                    RelevantFact(
                        fact = "CEO tier includes premium support",
                        isInTable = true,
                        classification = SourceClassification.OFFICIAL_LIVING_DOC
                    )
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "Who is the CEO?",
            sourceClassifications = sources
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // Should only use the OFFICIAL_LIVING_DOC non-table fact about Jane Doe
        assertTrue(output.answerFound, "Should find answer from valid facts")
        assertTrue(
            output.answer.contains("Jane", ignoreCase = true),
            "Should use the valid fact about Jane Doe, not the blog post about John Smith"
        )
    }

    @Test
    fun `should stream answer chunks correctly`() = runTest(testCoroutineDispatcher) {
        val source = ClassifiedSource(
            url = "https://example.com/about",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Jane Doe is the CEO of Example Corp",
                    isInTable = false,
                    classification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Example Corp is headquartered in San Francisco",
                    isInTable = false,
                    classification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "Who is the CEO of Example Corp?",
            sourceClassifications = listOf(source)
        )

        val items = agent.generateStream(input).toList()

        assertTrue(items.isNotEmpty(), "Should emit at least one item")
        
        // Last item should be Complete
        val lastItem = items.last()
        assertTrue(
            lastItem is PreviewAnswerStreamItem.Complete,
            "Last item should be Complete"
        )

        assertTrue(lastItem.answerFound, "Answer should be found")
        assertTrue(lastItem.reasoning.isNotBlank(), "Should have reasoning")
        assertNotNull(lastItem.tokenUsage)
    }

    @Test
    fun `streaming should return answerFound=false when no valid facts after filtering`() = runTest(testCoroutineDispatcher) {
        val source = ClassifiedSource(
            url = "https://example.com/blog/old-news",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "The product was launched in 2020",
                    isInTable = false,
                    classification = SourceClassification.OFFICIAL_SNAPSHOT
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "When was the product launched?",
            sourceClassifications = listOf(source)
        )

        val items = agent.generateStream(input).toList()

        assertTrue(items.isNotEmpty(), "Should emit at least one item")
        
        // Should only have Complete item (no chunks since no valid facts)
        assertEquals(1, items.size, "Should only have Complete item")
        
        val complete = items.first() as PreviewAnswerStreamItem.Complete
        assertFalse(complete.answerFound, "answerFound should be false")
        assertTrue(complete.reasoning.isNotBlank(), "Should have reasoning explaining why")
    }

    @Test
    fun `should handle mixed valid and invalid facts`() = runTest(testCoroutineDispatcher) {
        val sources = listOf(
            ClassifiedSource(
                url = "https://example.com/features",
                relevantFacts = listOf(
                    // Valid fact
                    RelevantFact(
                        fact = "The platform supports WhatsApp, Instagram, and Telegram",
                        isInTable = false,
                        classification = SourceClassification.OFFICIAL_LIVING_DOC
                    ),
                    // Invalid - in table
                    RelevantFact(
                        fact = "WhatsApp: Supported, Instagram: Supported",
                        isInTable = true,
                        classification = SourceClassification.OFFICIAL_LIVING_DOC
                    )
                )
            )
        )

        val input = PreviewAnswerSynthesisInput(
            query = "Does the platform support WhatsApp?",
            sourceClassifications = sources
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answerFound, "Should find answer from valid non-table fact")
        assertTrue(
            output.answer.contains("WhatsApp", ignoreCase = true),
            "Answer should mention WhatsApp"
        )
    }
}

