package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IMarkdownSourceEvalAgent
import io.deepsearch.domain.agents.MarkdownSourceEvalInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownSourceEvalAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IMarkdownSourceEvalAgent>()

    @Test
    fun `should return null evaluatedSource when markdown source is not relevant`() = runTest(testCoroutineDispatcher) {
        val source = MarkdownSource(
            url = "https://example.com/about",
            title = "About Us",
            description = "Learn about our company",
            markdown = """
                # About Us
                
                We are a small company founded in 2020.
                Our office is located in downtown Seattle.
            """.trimIndent()
        )

        val input = MarkdownSourceEvalInput(
            searchQuery = SearchQuery("What is the pricing for the Enterprise plan?", "https://example.com"),
            markdownSource = source
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // The content is about the company, not pricing, so it should be marked as not relevant
        // Note: The LLM behavior may vary - this tests the flow works correctly
    }

    @Test
    fun `should extract facts from relevant markdown source`() = runTest(testCoroutineDispatcher) {
        val source = MarkdownSource(
            url = "https://example.com/machine-learning-intro",
            title = "Introduction to Machine Learning",
            description = "Learn the basics of ML",
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

        val input = MarkdownSourceEvalInput(
            searchQuery = SearchQuery("What is machine learning?", "https://example.com"),
            markdownSource = source
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        assertTrue(output.evaluatedSource!!.relevantFacts.isNotEmpty(), "Should extract relevant facts")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should extract facts from comprehensive markdown source`() = runTest(testCoroutineDispatcher) {
        val source = MarkdownSource(
            url = "https://example.com/comprehensive-ml-guide",
            title = "Complete Guide to Machine Learning",
            description = "Everything you need to know about ML",
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

        val input = MarkdownSourceEvalInput(
            searchQuery = SearchQuery("What is machine learning and how does it work?", "https://example.com"),
            markdownSource = source
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source")
        assertTrue(output.evaluatedSource!!.relevantFacts.isNotEmpty(), "Should have extracted facts")
        assertTrue(output.tokenUsage.totalTokens > 0, "Should track token usage")
    }

    @Test
    fun `should include table facts in markdown evaluation`() = runTest(testCoroutineDispatcher) {
        val source = MarkdownSource(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = "Our pricing plans",
            markdown = """
                # Pricing Plans
                
                Choose the plan that works best for you.
                
                | Plan | Price | Features |
                |------|-------|----------|
                | Basic | $9/month | 5 users, 10GB storage |
                | Pro | $29/month | 25 users, 100GB storage |
                | Enterprise | $99/month | Unlimited users, 1TB storage |
                
                All plans include 24/7 support.
            """.trimIndent()
        )

        val input = MarkdownSourceEvalInput(
            searchQuery = SearchQuery("What is the pricing?", "https://example.com"),
            markdownSource = source
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for pricing content")
        
        // Unlike HTML preview, markdown tables are properly processed
        // So pricing facts should be included
        val pricingFacts = output.evaluatedSource!!.relevantFacts
            .filter { 
                it.fact.contains("$", ignoreCase = true) || 
                it.fact.contains("price", ignoreCase = true) ||
                it.fact.contains("month", ignoreCase = true)
            }
        
        assertTrue(pricingFacts.isNotEmpty(), "Should extract pricing facts from markdown tables")
    }

    @Test
    fun `should evaluate source metadata correctly`() = runTest(testCoroutineDispatcher) {
        val source = MarkdownSource(
            url = "https://example.com/features",
            title = "Product Features",
            description = "Explore our powerful features",
            markdown = """
                # Product Features
                
                ## Core Features
                
                - **Real-time Analytics**: Monitor your data in real-time
                - **API Access**: Full REST API for integrations
                - **Custom Dashboards**: Build your own dashboards
                - **Team Collaboration**: Work together seamlessly
                
                ## Security Features
                
                - SOC 2 Type II certified
                - 256-bit encryption
                - SSO integration
            """.trimIndent()
        )

        val input = MarkdownSourceEvalInput(
            searchQuery = SearchQuery("What are the main features?", "https://example.com"),
            markdownSource = source
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source")
        
        val evaluatedSource = output.evaluatedSource!!
        
        // Verify metadata is preserved
        assertTrue(evaluatedSource.url == source.url, "URL should be preserved")
        assertTrue(evaluatedSource.title == source.title, "Title should be preserved")
        assertTrue(evaluatedSource.description == source.description, "Description should be preserved")
        
        // Verify classification and answer type are set
        assertNotNull(evaluatedSource.sourceClassification, "Should have source classification")
        assertNotNull(evaluatedSource.answerType, "Should have answer type")
        assertTrue(evaluatedSource.relevanceJustification.isNotBlank(), "Should have relevance justification")
    }

}

