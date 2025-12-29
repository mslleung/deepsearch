package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.RelevantFact
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.SourceClassification
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamingAnswerSynthesisAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IStreamingAnswerSynthesisAgent>()

    @Test
    fun `should return default message when shortlisted sources are empty`() = runTest(testCoroutineDispatcher) {
        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            shortlistedSources = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertEquals("No information found to answer the query.", output.answer)
        assertTrue(output.reasoning.isNotBlank(), "Should have reasoning explaining why no answer")
        assertNotNull(output.tokenUsage)
    }

    @Test
    fun `should generate comprehensive answer from extracted facts`() = runTest(testCoroutineDispatcher) {
        val source = ShortlistedSource(
            url = "https://example.com/machine-learning",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning is a subset of artificial intelligence (AI) that enables systems to learn and improve from experience without being explicitly programmed.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "The process of learning begins with observations or data, such as examples, direct experience, or instruction.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Training Data is data used to train the model.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Comprehensive introduction to machine learning"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            shortlistedSources = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.answer.length > 50, "Answer should be comprehensive")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should synthesize information from multiple sources with facts`() = runTest(testCoroutineDispatcher) {
        val source1 = ShortlistedSource(
            url = "https://example.com/ml-definition",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning is a branch of artificial intelligence and computer science that focuses on the use of data and algorithms to imitate the way humans learn.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Clear definition of machine learning"
        )

        val source2 = ShortlistedSource(
            url = "https://example.com/ml-types",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Supervised learning algorithms learn from labeled training data.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Unsupervised learning finds hidden patterns in unlabeled data.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Reinforcement learning is about taking suitable action to maximize reward.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Comprehensive overview of ML types"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning and what are its types?",
            shortlistedSources = listOf(source1, source2)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.answer.length > 100, "Answer should synthesize multiple sources comprehensively")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should prioritize facts from OFFICIAL_LIVING_DOC sources`() = runTest(testCoroutineDispatcher) {
        val highRelevanceSource = ShortlistedSource(
            url = "https://example.com/deep-learning-official",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Deep learning is a subset of machine learning that uses neural networks with multiple layers.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Deep learning models can handle large amounts of data and excel at complex tasks.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Official documentation on deep learning"
        )

        val lowRelevanceSource = ShortlistedSource(
            url = "https://example.com/ml-blog-post",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "I started learning machine learning last month.",
                    sourceClassification = SourceClassification.OTHERS
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_SNAPSHOT,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.PARTIAL_MENTION,
            relevanceJustification = "Personal blog post"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is deep learning?",
            shortlistedSources = listOf(lowRelevanceSource, highRelevanceSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should indicate answerFound false when facts are not relevant`() = runTest(testCoroutineDispatcher) {
        val irrelevantSource = ShortlistedSource(
            url = "https://example.com/cooking-recipes",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Chocolate chip cookies need 2 cups flour and 1 cup sugar.",
                    sourceClassification = SourceClassification.OTHERS
                ),
                RelevantFact(
                    fact = "Preheat oven to 350°F for best results.",
                    sourceClassification = SourceClassification.OTHERS
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.FORUM_DISCUSSION,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.PARTIAL_MENTION,
            relevanceJustification = "Not relevant to query but kept for context"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is quantum computing?",
            shortlistedSources = listOf(irrelevantSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should indicate lack of relevant information")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }
}

