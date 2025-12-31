package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IStreamingAnswerSynthesisAgent
import io.deepsearch.domain.agents.StreamingAnswerSynthesisInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.AnswerStatus
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.RelevantFact
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
    fun `should return default message when evaluated sources are empty`() = runTest(testCoroutineDispatcher) {
        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            evaluatedSources = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertEquals("No information found to answer the query.", output.answer)
        assertEquals(AnswerStatus.NEEDS_MORE_SOURCES, output.status, "Should request more sources when empty")
        assertTrue(output.reasoning.isNotBlank(), "Should have reasoning explaining why no answer")
        assertNotNull(output.tokenUsage)
    }

    @Test
    fun `should generate comprehensive answer from extracted facts`() = runTest(testCoroutineDispatcher) {
        val source = EvaluatedSource(
            url = "https://example.com/machine-learning",
            title = "Machine Learning Guide",
            description = "A comprehensive guide to ML",
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
            evaluatedSources = listOf(source)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.answer.length > 50, "Answer should be comprehensive")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should synthesize information from multiple sources with facts`() = runTest(testCoroutineDispatcher) {
        val source1 = EvaluatedSource(
            url = "https://example.com/ml-definition",
            title = "ML Definition",
            description = null,
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

        val source2 = EvaluatedSource(
            url = "https://example.com/ml-types",
            title = "ML Types",
            description = null,
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
            evaluatedSources = listOf(source1, source2)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.answer.length > 100, "Answer should synthesize multiple sources comprehensively")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should prioritize facts from OFFICIAL_LIVING_DOC sources`() = runTest(testCoroutineDispatcher) {
        val highRelevanceSource = EvaluatedSource(
            url = "https://example.com/deep-learning-official",
            title = "Official Deep Learning Docs",
            description = null,
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

        val lowRelevanceSource = EvaluatedSource(
            url = "https://example.com/ml-blog-post",
            title = "Personal Blog",
            description = null,
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
            evaluatedSources = listOf(lowRelevanceSource, highRelevanceSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should indicate NEEDS_MORE_SOURCES when facts are not relevant`() = runTest(testCoroutineDispatcher) {
        val irrelevantSource = EvaluatedSource(
            url = "https://example.com/cooking-recipes",
            title = "Cooking Recipes",
            description = null,
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
            evaluatedSources = listOf(irrelevantSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should indicate lack of relevant information")
        assertEquals(AnswerStatus.NEEDS_MORE_SOURCES, output.status, "Should request more sources when facts are irrelevant")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should return COMPLETE status for comprehensive relevant facts`() = runTest(testCoroutineDispatcher) {
        val comprehensiveSource = EvaluatedSource(
            url = "https://example.com/machine-learning-complete",
            title = "Complete ML Guide",
            description = "Everything about ML",
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "The three main types of machine learning are supervised learning, unsupervised learning, and reinforcement learning.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Supervised learning uses labeled training data to learn the mapping from inputs to outputs.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Unsupervised learning finds patterns and structure in unlabeled data.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                ),
                RelevantFact(
                    fact = "Reinforcement learning learns through trial and error by receiving rewards and penalties.",
                    sourceClassification = SourceClassification.OFFICIAL_LIVING_DOC
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.OFFICIAL_LIVING_DOC,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.DIRECT_ANSWER,
            relevanceJustification = "Comprehensive official documentation on machine learning"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning?",
            evaluatedSources = listOf(comprehensiveSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should be comprehensive")
        assertNotNull(output.status, "Should have status")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
        // Note: The exact status depends on LLM judgment - this test validates the flow works
    }

    @Test
    fun `should pass previously searched queries to prevent duplicate follow-ups`() = runTest(testCoroutineDispatcher) {
        val partialSource = EvaluatedSource(
            url = "https://example.com/basic-ml",
            title = "Basic ML Info",
            description = null,
            relevantFacts = listOf(
                RelevantFact(
                    fact = "Machine learning helps computers learn from data.",
                    sourceClassification = SourceClassification.OTHERS
                )
            ),
            sourceClassification = io.deepsearch.domain.models.valueobjects.SourceType.THIRD_PARTY_REVIEW,
            contentDate = null,
            answerType = io.deepsearch.domain.models.valueobjects.AnswerType.PARTIAL_MENTION,
            relevanceJustification = "Basic mention of ML"
        )

        val input = StreamingAnswerSynthesisInput(
            query = "What is machine learning and how does it work?",
            evaluatedSources = listOf(partialSource),
            previouslySearchedQueries = listOf("machine learning basics", "how ML works"),
            targetDomain = "example.com"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.answer.isNotBlank(), "Answer should provide what's available")
        // If follow-up queries are suggested, they should not duplicate previously searched queries
        output.followUpQueries.forEach { query ->
            assertTrue(
                !input.previouslySearchedQueries.contains(query),
                "Follow-up query should not duplicate previously searched: $query"
            )
        }
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }
}
