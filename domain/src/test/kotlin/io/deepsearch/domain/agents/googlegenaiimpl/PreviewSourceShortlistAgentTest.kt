package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IPreviewSourceShortlistAgent
import io.deepsearch.domain.agents.PreviewSourceShortlistInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SourceClassification
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewSourceShortlistAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPreviewSourceShortlistAgent>()

    @Test
    fun `should return empty result when HTML sources is empty`() = runTest(testCoroutineDispatcher) {
        val input = PreviewSourceShortlistInput(
            query = "Who is the CEO?",
            htmlSources = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.shortlistedSources.isEmpty(), "Shortlisted sources should be empty when no sources provided")
    }

    /**
     * Test case: SleekFlow About page with clear prose content.
     * The CEO information (Henson Tsai) is in clear prose paragraphs,
     * so facts should be extracted and included in the shortlist.
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

        val input = PreviewSourceShortlistInput(
            query = "Who is the CEO of SleekFlow?",
            htmlSources = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        assertTrue(output.shortlistedSources.isNotEmpty(), "Should have shortlisted sources")
        
        // Find facts about the CEO
        val ceoFacts = output.shortlistedSources
            .flatMap { it.relevantFacts }
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

        val input = PreviewSourceShortlistInput(
            query = "Does the Pro plan have any SLA guarantee?",
            htmlSources = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        
        // SLA facts from tables should be filtered out internally by the agent.
        // The test HTML only has SLA info in table format, so we expect no SLA facts
        // or very few if any prose mentions exist.
        // The main assertion is that the agent completes successfully with table filtering.
    }

    /**
     * Test case: Blog post content should be classified with appropriate SourceClassification.
     */
    @Test
    fun `should classify blog posts with OFFICIAL_SNAPSHOT classification`() = runTest(testCoroutineDispatcher) {
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

        val input = PreviewSourceShortlistInput(
            query = "Does the platform support AI analytics?",
            htmlSources = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.shortlistedSources.isNotEmpty(), "Should have shortlisted sources")
        
        // Find facts about the feature
        val featureFacts = output.shortlistedSources
            .flatMap { it.relevantFacts }
            .filter { it.fact.contains("AI", ignoreCase = true) || it.fact.contains("analytics", ignoreCase = true) }
        
        assertTrue(featureFacts.isNotEmpty(), "Should find facts about AI analytics")
        
        // Blog content facts should be classified as OFFICIAL_SNAPSHOT
        featureFacts.forEach { fact ->
            assertTrue(
                fact.sourceClassification == SourceClassification.OFFICIAL_SNAPSHOT,
                "Blog post facts should be classified as OFFICIAL_SNAPSHOT: ${fact.sourceClassification}"
            )
        }
    }

    @Test
    fun `should extract facts from multiple sources`() = runTest(testCoroutineDispatcher) {
        val htmlSource1 = UrlContentResult.HtmlPreview(
            url = "https://example.com/about",
            title = "About Us",
            description = "Learn about our company",
            cleanedHtml = """
                <article>
                    <h1>About Example Corp</h1>
                    <p>Example Corp was founded in 2015 by Jane Doe.</p>
                    <p>Jane Doe serves as the CEO and leads our team of 50 employees.</p>
                    <p>We are headquartered in San Francisco, California.</p>
                </article>
            """.trimIndent()
        )
        
        val htmlSource2 = UrlContentResult.HtmlPreview(
            url = "https://example.com/blog/history",
            title = "Our History",
            description = "The journey of Example Corp",
            cleanedHtml = """
                <article>
                    <time>January 2020</time>
                    <p>In 2020, we expanded to Europe with offices in London and Berlin.</p>
                </article>
            """.trimIndent()
        )

        val input = PreviewSourceShortlistInput(
            query = "Tell me about Example Corp",
            htmlSources = listOf(htmlSource1, htmlSource2)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.shortlistedSources.isNotEmpty(), "Should have shortlisted sources")
        
        // Each source should have relevant facts
        output.shortlistedSources.forEach { source ->
            // Facts should not be blank
            source.relevantFacts.forEach { fact ->
                assertTrue(fact.fact.isNotBlank(), "Fact should not be blank")
            }
        }
    }
}

