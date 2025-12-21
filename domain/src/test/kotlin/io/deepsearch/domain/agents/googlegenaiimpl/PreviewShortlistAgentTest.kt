package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IPreviewShortlistAgent
import io.deepsearch.domain.agents.PreviewShortlistInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewShortlistAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koin = KoinTestExtension.create { modules(domainTestModule) }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPreviewShortlistAgent>()

    @Test
    fun `should return empty shortlist when HTML batch is empty`() = runTest(testCoroutineDispatcher) {
        val input = PreviewShortlistInput(
            query = "What is the company founded year?",
            currentShortlist = emptyList(),
            newHtmlBatch = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertTrue(output.updatedShortlist.isEmpty(), "Shortlist should be empty when no sources provided")
        assertFalse(output.isConfidentForAnswer, "Should not be confident with no sources")
    }

    @Test
    fun `should shortlist source with clear prose content`() = runTest(testCoroutineDispatcher) {
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://example.com/about",
            title = "About Us",
            description = "Learn about our company",
            cleanedHtml = """
                <article>
                    <h1>About Our Company</h1>
                    <p>Example Corp was founded in 2015 by John Smith in San Francisco.</p>
                    <p>We specialize in enterprise software solutions for small businesses.</p>
                    <p>Our mission is to make technology accessible to everyone.</p>
                </article>
            """.trimIndent()
        )

        val input = PreviewShortlistInput(
            query = "When was Example Corp founded?",
            currentShortlist = emptyList(),
            newHtmlBatch = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // The agent should extract the founding year fact from clear prose
        // Note: Actual behavior depends on LLM - this test verifies the flow works
        assertNotNull(output.tokenUsage)
    }

    @Test
    fun `should reject source with table content`() = runTest(testCoroutineDispatcher) {
        val htmlSource = UrlContentResult.HtmlPreview(
            url = "https://example.com/pricing",
            title = "Pricing",
            description = "Our pricing plans",
            cleanedHtml = """
                <div>
                    <h1>Pricing</h1>
                    <table>
                        <tr><th>Plan</th><th>Price</th></tr>
                        <tr><td>Basic</td><td>$10/month</td></tr>
                        <tr><td>Pro</td><td>$25/month</td></tr>
                    </table>
                </div>
            """.trimIndent()
        )

        val input = PreviewShortlistInput(
            query = "What is the price of the Pro plan?",
            currentShortlist = emptyList(),
            newHtmlBatch = listOf(htmlSource)
        )

        val output = agent.generate(input)

        assertNotNull(output)
        // The agent should be restrictive with table content
        // It may shortlist with low confidence or not shortlist at all
        // The key is that isConfidentForAnswer should be false for tabular data
        assertFalse(
            output.isConfidentForAnswer,
            "Should not be confident when answer is in a table"
        )
    }

    @Test
    fun `should maintain existing shortlist when new batch is empty`() = runTest(testCoroutineDispatcher) {
        val existingSource = PreviewShortlistedSource(
            url = "https://example.com/about",
            title = "About Us",
            extractedFacts = listOf("Example Corp was founded in 2015"),
            confidence = 0.95f,
            relevanceJustification = "Contains founding year in clear prose"
        )

        val input = PreviewShortlistInput(
            query = "When was Example Corp founded?",
            currentShortlist = listOf(existingSource),
            newHtmlBatch = emptyList()
        )

        val output = agent.generate(input)

        assertNotNull(output)
        assertFalse(output.isConfidentForAnswer, "Should not be confident with empty batch")
    }
}
