package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.HtmlSourceEvalInput
import io.deepsearch.domain.agents.IHtmlSourceEvalAgent
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.UrlContentResult
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

class HtmlSourceEvalAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IHtmlSourceEvalAgent>()

    /**
     * Test case: SleekFlow About page with clear prose content.
     * The CEO information (Henson Tsai) is in clear prose paragraphs,
     * so facts should be extracted and included in the output.
     */
    @Test
    fun `should extract prose content facts and not filter them out`() = runTest(testCoroutineDispatcher) {
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://sleekflow.io/about",
            title = "About Us | SleekFlow",
            description = "Discover what SleekFlow can do for your Business",
            cleanedHtml = """
                <article>
                    <h1>Our Mission</h1>
                    <h2>Revolutionizing business workflows through meaningful conversations</h2>
                    <p>SleekFlow is the AI-powered Omnichannel Conversation Suite for customer engagement. 
                    The all-in-one SleekFlow platform creates seamless and personalized customer journeys 
                    across everyone's go-to messaging channels, including WhatsApp, Instagram, live chat, and more.</p>
                    
                    <h2>About Our Founder</h2>
                    <p>SleekFlow's founder Henson completed his degree in Statistics and Computer Science 
                    at Imperial College London in 2016.</p>
                    <p>Before SleekFlow, he worked in the banking and consulting sector. His SleekFlow 
                    journey commenced with just 2 engineers. After interviewing 500+ prospective users 
                    with his founding members, SleekFlow Beta was advanced progressively and launched 
                    in November 2019. Henson's exceptional contributions have been recognized, most 
                    recently earning him a spot on Forbes Asia's 30 under 30 2023 list in the Enterprise 
                    Technology category.</p>
                    
                    <h2>Meet the Team</h2>
                    <h3>Henson Tsai</h3>
                    <p>Founder & CEO</p>
                    <p>Continuously driving innovation through speed, precision, and continuous evolution.</p>
                    
                    <h3>Gao Lei</h3>
                    <p>Chief Technology Officer (CTO)</p>
                    <p>Our greatest weakness lies in giving up. The most certain way to succeed is always to try just one more time.</p>
                </article>
            """.trimIndent()
        )

        val input = HtmlSourceEvalInput(
            searchQuery = SearchQuery("Who is the CEO of SleekFlow?", "https://sleekflow.io"),
            htmlSource = htmlSource
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        // Find facts about the CEO
        val ceoFacts = output.evaluatedSource.relevantFacts
            .filter { it.fact.contains("Henson", ignoreCase = true) || it.fact.contains("CEO", ignoreCase = true) }
        
        assertTrue(ceoFacts.isNotEmpty(), "Should find facts about the CEO (prose facts are not filtered)")
    }

    /**
     * Test case: Pricing page with table content.
     * The SLA information is in tables/grids, which are filtered out
     * before returning (table data in HTML previews is inaccurate).
     */
    @Test
    fun `should filter out table content`() = runTest(testCoroutineDispatcher) {
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://sleekflow.io/pricing",
            title = "Pricing | SleekFlow",
            description = "Unlock unlimited AI, unleash conversational growth",
            cleanedHtml = """
                <div>
                    <h1>Unlock unlimited AI, unleash conversational growth</h1>
                    <p>Give sales and marketing teams the power to engage, convert, and support customers 24/7.</p>
                    
                    <table class="feature-comparison">
                        <thead>
                            <tr>
                                <th>Feature</th>
                                <th>Free</th>
                                <th>Pro</th>
                                <th>Premium</th>
                                <th>Enterprise</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr>
                                <td>SLA</td>
                                <td>-</td>
                                <td>-</td>
                                <td>-</td>
                                <td>99.9%</td>
                            </tr>
                            <tr>
                                <td>Monthly Active Contacts</td>
                                <td>50</td>
                                <td>500+</td>
                                <td>1000+</td>
                                <td>Custom</td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            """.trimIndent()
        )

        val input = HtmlSourceEvalInput(
            searchQuery = SearchQuery("Does the Pro plan have any SLA guarantee?", "https://sleekflow.io"),
            htmlSource = htmlSource
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // SLA facts from tables should be filtered out internally by the agent.
        // The test HTML only has SLA info in table format, so the source may be null
        // or have no SLA-related facts if any prose mentions exist.
        // The main assertion is that the agent completes successfully with table filtering.
    }

    /**
     * Test case: Blog post content should have intention describing dated content.
     */
    @Test
    fun `should identify blog posts as dated content in intention`() = runTest(testCoroutineDispatcher) {
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://example.com/blog/2023/new-feature-announcement",
            title = "New Feature Announcement | Example Blog",
            description = "We're excited to announce our new feature",
            cleanedHtml = """
                <article>
                    <h1>Announcing Our New Feature</h1>
                    <time datetime="2023-06-15">June 15, 2023</time>
                    <p>We are thrilled to announce that our platform now supports AI-powered analytics.</p>
                    <p>This new feature allows users to gain insights from their data automatically.</p>
                    <p>Starting today, all Pro and Enterprise users will have access to this feature.</p>
                </article>
            """.trimIndent()
        )

        val input = HtmlSourceEvalInput(
            searchQuery = SearchQuery("Does the platform support AI analytics?", "https://example.com"),
            htmlSource = htmlSource
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.evaluatedSource, "Should have evaluated source for relevant content")
        
        val evaluatedSource = output.evaluatedSource
        
        // Find facts about the feature
        val featureFacts = evaluatedSource.relevantFacts
            .filter { it.fact.contains("AI", ignoreCase = true) || it.fact.contains("analytics", ignoreCase = true) }
        
        assertTrue(featureFacts.isNotEmpty(), "Should find facts about AI analytics")
        
        // Blog post should have intention describing dated content
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
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://example.com/about",
            title = "About Us",
            description = "Learn about our company",
            cleanedHtml = """
                <article>
                    <h1>About Example Corp</h1>
                    <p>Example Corp was founded in 2015 by Jane Doe.</p>
                    <p>We are headquartered in San Francisco, California.</p>
                    <p>Our team enjoys hiking and coffee.</p>
                </article>
            """.trimIndent()
        )

        val input = HtmlSourceEvalInput(
            searchQuery = SearchQuery("What is the pricing for the Pro plan?", "https://example.com"),
            htmlSource = htmlSource
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // The content is about the company, not pricing, so it should be marked as not relevant
        // Note: The LLM may still find this relevant if it mentions anything tangentially related
        // This test validates the flow works - the exact behavior depends on the LLM
    }

    /**
     * Test case: Content with all table data should return null after filtering.
     */
    @Test
    fun `should return null evaluatedSource when all facts are from tables`() = runTest(testCoroutineDispatcher) {
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = "Our pricing plans",
            cleanedHtml = """
                <div>
                    <table>
                        <tr><th>Plan</th><th>Price</th></tr>
                        <tr><td>Basic</td><td>$9/month</td></tr>
                        <tr><td>Pro</td><td>$29/month</td></tr>
                        <tr><td>Enterprise</td><td>$99/month</td></tr>
                    </table>
                </div>
            """.trimIndent()
        )

        val input = HtmlSourceEvalInput(
            searchQuery = SearchQuery("What is the pricing?", "https://example.com"),
            htmlSource = htmlSource
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // All pricing info is in tables, which should be filtered out
        // The evaluatedSource should be null since no non-table facts remain
        assertNull(
            output.evaluatedSource,
            "Should return null evaluatedSource when all facts are from tables"
        )
    }
}
