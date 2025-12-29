package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IPreviewClassificationAgent
import io.deepsearch.domain.agents.PreviewClassificationInput
import io.deepsearch.domain.agents.SourceClassification
import io.deepsearch.domain.config.domainTestModule
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

class PreviewClassificationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPreviewClassificationAgent>()

    @Test
    fun `should return empty result when HTML sources is empty`() = runTest(testCoroutineDispatcher) {
        val input = PreviewClassificationInput(
            query = "Who is the CEO?",
            htmlSources = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.sourceClassifications.isEmpty(), "Source classifications should be empty when no sources provided")
    }

    /**
     * Test case: SleekFlow About page with clear prose content.
     * The CEO information (Henson Tsai) is in clear prose paragraphs,
     * so the agent should classify it as OFFICIAL_LIVING_DOC with isInTable=false.
     * 
     * Source: https://sleekflow.io/about
     */
    @Test
    fun `should classify prose content as OFFICIAL_LIVING_DOC with isInTable=false`() = runTest(testCoroutineDispatcher) {
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

        val input = PreviewClassificationInput(
            query = "Who is the CEO of SleekFlow?",
            htmlSources = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        assertTrue(output.sourceClassifications.isNotEmpty(), "Should have source classifications")
        
        // Find facts about the CEO
        val ceoFacts = output.sourceClassifications
            .flatMap { it.relevantFacts }
            .filter { it.fact.contains("Henson", ignoreCase = true) || it.fact.contains("CEO", ignoreCase = true) }
        
        assertTrue(ceoFacts.isNotEmpty(), "Should find facts about the CEO")
        
        // CEO facts should be from prose (not table) and OFFICIAL_LIVING_DOC
        ceoFacts.forEach { fact ->
            assertTrue(
                !fact.isInTable,
                "CEO fact should not be marked as table content: ${fact.fact}"
            )
            assertTrue(
                fact.classification == SourceClassification.OFFICIAL_LIVING_DOC,
                "About page should be classified as OFFICIAL_LIVING_DOC: ${fact.classification}"
            )
        }
    }

    /**
     * Test case: Pricing page with table content.
     * The SLA information is in tables/grids,
     * so the agent should mark isInTable=true.
     * 
     * Source: https://sleekflow.io/pricing
     */
    @Test
    fun `should mark table content with isInTable=true`() = runTest(testCoroutineDispatcher) {
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

        val input = PreviewClassificationInput(
            query = "Does the Pro plan have any SLA guarantee?",
            htmlSources = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertNotNull(output.tokenUsage)
        assertTrue(output.sourceClassifications.isNotEmpty(), "Should have source classifications")
        
        // Find facts about SLA
        val slaFacts = output.sourceClassifications
            .flatMap { it.relevantFacts }
            .filter { it.fact.contains("SLA", ignoreCase = true) }
        
        assertTrue(slaFacts.isNotEmpty(), "Should find facts about SLA")
        
        // SLA facts from table should be marked as isInTable=true
        slaFacts.forEach { fact ->
            assertTrue(
                fact.isInTable,
                "SLA fact from table should be marked as table content: ${fact.fact}"
            )
        }
    }

    /**
     * Test case: Blog post content should be classified as OFFICIAL_SNAPSHOT.
     */
    @Test
    fun `should classify blog posts as OFFICIAL_SNAPSHOT`() = runTest(testCoroutineDispatcher) {
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

        val input = PreviewClassificationInput(
            query = "Does the platform support AI analytics?",
            htmlSources = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.sourceClassifications.isNotEmpty(), "Should have source classifications")
        
        // Find facts about the feature
        val featureFacts = output.sourceClassifications
            .flatMap { it.relevantFacts }
            .filter { it.fact.contains("AI", ignoreCase = true) || it.fact.contains("analytics", ignoreCase = true) }
        
        assertTrue(featureFacts.isNotEmpty(), "Should find facts about AI analytics")
        
        // Blog content should be classified as OFFICIAL_SNAPSHOT
        featureFacts.forEach { fact ->
            assertTrue(
                fact.classification == SourceClassification.OFFICIAL_SNAPSHOT,
                "Blog post should be classified as OFFICIAL_SNAPSHOT: ${fact.classification}"
            )
        }
    }

    @Test
    fun `should classify each source with multiple facts`() = runTest(testCoroutineDispatcher) {
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

        val input = PreviewClassificationInput(
            query = "Tell me about Example Corp",
            htmlSources = listOf(htmlSource1, htmlSource2)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.sourceClassifications.isNotEmpty(), "Should have source classifications")
        
        // Should have classifications for both sources
        val urls = output.sourceClassifications.map { it.url }
        assertTrue(
            urls.any { it.contains("about") },
            "Should have classification for about page"
        )
        
        // Each source should have relevant facts
        output.sourceClassifications.forEach { source ->
            // May or may not have facts depending on relevance, but structure should be valid
            source.relevantFacts.forEach { fact ->
                assertTrue(fact.fact.isNotBlank(), "Fact should not be blank")
            }
        }
    }
}

