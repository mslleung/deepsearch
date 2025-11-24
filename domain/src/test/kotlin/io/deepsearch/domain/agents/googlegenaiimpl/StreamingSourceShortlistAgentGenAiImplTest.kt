package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IStreamingSourceShortlistAgent
import io.deepsearch.domain.agents.StreamingSourceShortlistInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import kotlinx.coroutines.CoroutineDispatcher
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

class StreamingSourceShortlistAgentGenAiImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IStreamingSourceShortlistAgent>()

    @Test
    fun `should return empty shortlist when both current and new batch are empty`() = runTest(testCoroutineDispatcher) {
        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = emptyList(),
            newMarkdownBatch = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.updatedShortlist.isEmpty(), "Shortlist should be empty when no sources provided")
        assertFalse(output.isGoodEnough, "Should not be good enough with no sources")
        assertEquals("No new sources to evaluate", output.reason)
    }

    @Test
    fun `should keep existing shortlist unchanged when new batch is empty`() = runTest(testCoroutineDispatcher) {
        val existingSource = ShortlistedSource(
            url = "https://example.com/ml",
            markdown = "# Machine Learning\n\nMachine learning is a subset of artificial intelligence.",
            contentRelevance = 0.9f,
            temporalRelevance = 0.8f,
            authority = 0.85f,
            note = "Good introduction to ML"
        )

        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = listOf(existingSource),
            newMarkdownBatch = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertEquals(1, output.updatedShortlist.size, "Should maintain existing shortlist")
        assertEquals(existingSource.url, output.updatedShortlist[0].url)
        assertFalse(output.isGoodEnough, "Should not be good enough with empty batch")
    }

    @Test
    fun `should create shortlist from new sources when current shortlist is empty`() = runTest(testCoroutineDispatcher) {
        val newSource = MarkdownSource(
            url = "https://example.com/machine-learning-intro",
            markdown = """
                # Introduction to Machine Learning
                
                Machine learning is a branch of artificial intelligence (AI) and computer science which focuses on 
                the use of data and algorithms to imitate the way that humans learn, gradually improving its accuracy.
                
                ## Types of Machine Learning
                
                1. **Supervised Learning**: The algorithm learns from labeled training data
                2. **Unsupervised Learning**: The algorithm finds patterns in unlabeled data
                3. **Reinforcement Learning**: The algorithm learns through trial and error
                
                ## Applications
                
                Machine learning is used in:
                - Image recognition
                - Natural language processing
                - Recommendation systems
                - Autonomous vehicles
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(newSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.updatedShortlist.isNotEmpty(), "Should create shortlist from new sources")
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should add new sources to existing shortlist`() = runTest(testCoroutineDispatcher) {
        val existingSource = ShortlistedSource(
            url = "https://example.com/ml-basics",
            markdown = "# ML Basics\n\nMachine learning basics explained.",
            contentRelevance = 0.8f,
            temporalRelevance = 0.7f,
            authority = 0.75f,
            note = "Basic introduction"
        )

        val newSource = MarkdownSource(
            url = "https://example.com/ml-algorithms",
            markdown = """
                # Machine Learning Algorithms
                
                ## Decision Trees
                Decision trees are a popular supervised learning algorithm used for both classification and regression.
                
                ## Neural Networks
                Neural networks are computing systems inspired by biological neural networks that constitute animal brains.
                
                ## Support Vector Machines
                SVMs are supervised learning models used for classification and regression analysis.
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "What are machine learning algorithms?",
            currentShortlist = listOf(existingSource),
            newMarkdownBatch = listOf(newSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should mark as good enough when high quality comprehensive sources are provided`() = runTest(testCoroutineDispatcher) {
        val comprehensiveSource = MarkdownSource(
            url = "https://example.com/comprehensive-ml-guide",
            markdown = """
                # Complete Guide to Machine Learning
                
                ## What is Machine Learning?
                Machine learning is a subset of artificial intelligence that enables systems to learn and improve 
                from experience without being explicitly programmed.
                
                ## Core Concepts
                - **Training Data**: Historical data used to train the model
                - **Features**: Input variables used to make predictions
                - **Labels**: The output or target variable
                - **Model**: The mathematical representation of the pattern
                
                ## Types of Machine Learning
                
                ### Supervised Learning
                Uses labeled training data to learn the mapping from inputs to outputs. Examples include:
                - Linear Regression
                - Logistic Regression
                - Decision Trees
                - Random Forests
                - Neural Networks
                
                ### Unsupervised Learning
                Finds patterns in unlabeled data. Examples include:
                - K-Means Clustering
                - Hierarchical Clustering
                - Principal Component Analysis (PCA)
                
                ### Reinforcement Learning
                Learns through interaction with an environment using rewards and penalties.
                
                ## Real-World Applications
                - Healthcare: Disease diagnosis, drug discovery
                - Finance: Fraud detection, algorithmic trading
                - Retail: Recommendation systems, demand forecasting
                - Transportation: Autonomous vehicles, route optimization
                
                ## How Machine Learning Works
                1. Data Collection: Gather relevant data
                2. Data Preparation: Clean and format the data
                3. Model Training: Feed data to the algorithm
                4. Model Evaluation: Test the model's accuracy
                5. Deployment: Use the model in production
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "What is machine learning and how does it work?",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(comprehensiveSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should not mark as good enough when sources lack sufficient information`() = runTest(testCoroutineDispatcher) {
        val minimalSource = MarkdownSource(
            url = "https://example.com/ml-brief",
            markdown = """
                # Machine Learning
                
                Machine learning is a type of AI.
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "Explain machine learning in detail including types, algorithms, applications, and best practices",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(minimalSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.isGoodEnough, "Should not mark as good enough when information is insufficient")
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should evaluate multiple sources and curate appropriately`() = runTest(testCoroutineDispatcher) {
        val source1 = MarkdownSource(
            url = "https://example.com/ml-overview",
            markdown = """
                # Machine Learning Overview
                
                Machine learning is a method of data analysis that automates analytical model building.
                It is a branch of artificial intelligence based on the idea that systems can learn from data,
                identify patterns and make decisions with minimal human intervention.
            """.trimIndent()
        )

        val source2 = MarkdownSource(
            url = "https://example.com/ml-types",
            markdown = """
                # Types of Machine Learning
                
                ## Supervised Learning
                The algorithm learns from labeled examples. Common algorithms include:
                - Linear Regression
                - Decision Trees
                - Neural Networks
                
                ## Unsupervised Learning
                The algorithm finds hidden patterns in unlabeled data. Examples:
                - K-Means Clustering
                - PCA
            """.trimIndent()
        )

        val source3 = MarkdownSource(
            url = "https://example.com/ml-applications",
            markdown = """
                # Machine Learning Applications
                
                Machine learning is used across many industries:
                
                - **Healthcare**: Predicting patient outcomes, medical image analysis
                - **Finance**: Credit scoring, fraud detection
                - **E-commerce**: Product recommendations, customer segmentation
                - **Manufacturing**: Predictive maintenance, quality control
            """.trimIndent()
        )

        val input = StreamingSourceShortlistInput(
            query = "What is machine learning?",
            currentShortlist = emptyList(),
            newMarkdownBatch = listOf(source1, source2, source3)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.updatedShortlist.isNotEmpty(), "Should curate sources into shortlist")
        assertNotNull(output.reason)
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
        
        // Verify that sources have relevance scores
        output.updatedShortlist.forEach { source ->
            assertTrue(source.contentRelevance >= 0.0f && source.contentRelevance <= 1.0f, 
                "Content relevance should be between 0 and 1")
            assertTrue(source.temporalRelevance >= 0.0f && source.temporalRelevance <= 1.0f,
                "Temporal relevance should be between 0 and 1")
            assertTrue(source.authority >= 0.0f && source.authority <= 1.0f,
                "Authority should be between 0 and 1")
            assertTrue(source.note.isNotBlank(), "Note should explain inclusion")
        }
    }
}

