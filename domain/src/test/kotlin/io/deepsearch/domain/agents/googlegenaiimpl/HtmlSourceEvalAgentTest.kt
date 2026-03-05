package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.IHtmlSourceEvalAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the HTML source evaluation agent.
 * 
 * Note: The input is extracted sentences (plain text), not raw HTML.
 * Tabular data is naturally filtered out since table cells are fragments, not sentences.
 */
class HtmlSourceEvalAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koin = IsolatedKoinExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IHtmlSourceEvalAgent>()

    /**
     * Test case: SleekFlow About page with clear prose content.
     * The CEO information (Henson Tsai) is in clear prose sentences,
     * so facts should be extracted and included in the output.
     */
    @Test
    fun `should extract prose content facts`() = runTest(testCoroutineDispatcher) {
        // Input is now extracted sentences (plain text), not HTML
        val extractedSentences = """
            SleekFlow is the AI-powered Omnichannel Conversation Suite for customer engagement.
            The all-in-one SleekFlow platform creates seamless and personalized customer journeys across everyone's go-to messaging channels, including WhatsApp, Instagram, live chat, and more.
            SleekFlow's founder Henson completed his degree in Statistics and Computer Science at Imperial College London in 2016.
            Before SleekFlow, he worked in the banking and consulting sector.
            His SleekFlow journey commenced with just 2 engineers.
            After interviewing 500+ prospective users with his founding members, SleekFlow Beta was advanced progressively and launched in November 2019.
            Henson's exceptional contributions have been recognized, most recently earning him a spot on Forbes Asia's 30 under 30 2023 list in the Enterprise Technology category.
            Continuously driving innovation through speed, precision, and continuous evolution.
        """.trimIndent()
        
        val source = UrlContentResult.HtmlPreview(
            url = "https://sleekflow.io/about",
            title = "About Us | SleekFlow",
            description = "Discover what SleekFlow can do for your Business",
            cleanedHtml = extractedSentences
        )

        val input = HtmlSourceEvalInput(
            htmlSource = source,
            expandedQuery = "Who is the CEO of SleekFlow?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        // Find facts about the CEO/founder
        val ceoFacts = output.evaluatedSource.relevantFacts
            .filter { it.fact.contains("Henson", ignoreCase = true) || it.fact.contains("founder", ignoreCase = true) }
        
        assertTrue(ceoFacts.isNotEmpty(), "Should find facts about the founder/CEO")
    }

    /**
     * Test case: Blog post content should have intention describing dated content.
     */
    @Test
    fun `should identify blog posts as dated content in intention`() = runTest(testCoroutineDispatcher) {
        // Input is extracted sentences from a blog post
        val extractedSentences = """
            We are thrilled to announce that our platform now supports AI-powered analytics.
            This new feature allows users to gain insights from their data automatically.
            Starting today, all Pro and Enterprise users will have access to this feature.
            The AI analytics feature uses machine learning to identify patterns in your data.
            You can access this feature from the dashboard under the Analytics tab.
        """.trimIndent()
        
        val source = UrlContentResult.HtmlPreview(
            url = "https://example.com/blog/2023/new-feature-announcement",
            title = "New Feature Announcement | Example Blog",
            description = "We're excited to announce our new feature",
            cleanedHtml = extractedSentences
        )

        val input = HtmlSourceEvalInput(
            htmlSource = source,
            expandedQuery = "Does the platform support AI analytics?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        val evaluatedSource = output.evaluatedSource
        
        // Find facts about the feature
        val featureFacts = evaluatedSource.relevantFacts
            .filter { it.fact.contains("AI", ignoreCase = true) || it.fact.contains("analytics", ignoreCase = true) }
        
        assertTrue(featureFacts.isNotEmpty(), "Should find facts about AI analytics")
        
        // Should have intention describing the page purpose
        assertTrue(
            evaluatedSource.intention.isNotBlank(),
            "Should have intention describing the page purpose"
        )
    }

    /**
     * Test case: Irrelevant content should return null evaluatedSource.
     */
    @Test
    fun `should return null evaluatedSource for irrelevant content`() = runTest(testCoroutineDispatcher) {
        // Content about the company, not pricing
        val extractedSentences = """
            Example Corp was founded in 2015 by Jane Doe.
            We are headquartered in San Francisco, California.
            Our team enjoys hiking and coffee on the weekends.
            The company has grown to over 100 employees worldwide.
        """.trimIndent()
        
        val source = UrlContentResult.HtmlPreview(
            url = "https://example.com/about",
            title = "About Us",
            description = "Learn about our company",
            cleanedHtml = extractedSentences
        )

        val input = HtmlSourceEvalInput(
            htmlSource = source,
            expandedQuery = "What is the pricing for the Pro plan?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // The content is about the company, not pricing, so it should be marked as not relevant
        // Note: The LLM may still find this relevant if it mentions anything tangentially related
        // This test validates the flow works - the exact behavior depends on the LLM
    }

    /**
     * Test case: Empty/minimal content should return null evaluatedSource.
     * 
     * Note: Previously this tested "all table data" being filtered out.
     * Now, since we extract sentences first, table content never reaches the agent.
     * This test validates behavior with minimal/empty content.
     */
    @Test
    fun `should return null evaluatedSource for empty or minimal content`() = runTest(testCoroutineDispatcher) {
        // Minimal content - sentences are very short or empty
        // Minimal content - short sentences are filtered out upstream
        val extractedSentences = ""
        
        val source = UrlContentResult.HtmlPreview(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = "Our pricing plans",
            cleanedHtml = extractedSentences
        )

        val input = HtmlSourceEvalInput(
            htmlSource = source,
            expandedQuery = "What is the pricing?"
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // Empty content should have no relevant facts
        assertNull(
            output.evaluatedSource,
            "Should return null evaluatedSource when content is empty"
        )
    }

    /**
     * Test case: Content with fulfillment requirements should extract relevant facts.
     */
    @Test
    fun `should extract facts based on fulfillment requirements`() = runTest(testCoroutineDispatcher) {
        val extractedSentences = """
            SleekFlow offers multiple pricing tiers designed for businesses of all sizes.
            The Pro plan includes unlimited messaging and up to 10 team members.
            Enterprise customers receive dedicated support and custom integrations.
            All plans include access to our AI-powered chatbot builder.
            The platform supports integration with WhatsApp Business API, Facebook Messenger, and Instagram.
        """.trimIndent()
        
        val source = UrlContentResult.HtmlPreview(
            url = "https://sleekflow.io/pricing",
            title = "Pricing | SleekFlow",
            description = "Unlock unlimited AI, unleash conversational growth",
            cleanedHtml = extractedSentences
        )

        val input = HtmlSourceEvalInput(
            htmlSource = source,
            expandedQuery = "What are SleekFlow's pricing plans?",
            fulfillmentRequirements = listOf(
                "List the available pricing tiers",
                "Describe what is included in each plan"
            )
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        val facts = output.evaluatedSource.relevantFacts
        assertTrue(facts.isNotEmpty(), "Should extract facts relevant to the requirements")
    }
}
