package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITextLinkDiscoveryAgent
import io.deepsearch.domain.agents.TextLinkDiscoveryInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.LinkSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextLinkDiscoveryAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ITextLinkDiscoveryAgent>()

    @Test
    fun `discovers relevant links from text content`() = runTest(testCoroutineDispatcher) {
        // Given
        val sourceUrl = "https://www.example.com/"
        val query = "pricing information"
        val text = """
            Welcome to our website! Here are some useful links:
            
            Check out our pricing page at https://www.example.com/pricing for detailed information.
            You can also visit https://www.example.com/products to see our product catalog.
            For support, go to https://www.example.com/support.
            
            We also recommend https://www.other-site.com/external which has more resources.
        """.trimIndent()

        // When
        val output = agent.generate(TextLinkDiscoveryInput(text, sourceUrl, query))

        // Then
        assertTrue(output.links.isNotEmpty(), "Should discover links from text")
        output.links.forEach { link ->
            assertEquals(LinkSource.FILE_CONTENT, link.source)
            assertTrue(link.url.startsWith("https://www.example.com/"), "Link should be from the same domain")
            assertTrue(link.reason.isNotBlank(), "Should provide reason for link inclusion")
        }
        // The pricing link should be most relevant
        assertTrue(
            output.links.any { it.url.contains("pricing") },
            "Should include pricing link as relevant to query"
        )
    }

    @Test
    fun `filters out external domain URLs`() = runTest(testCoroutineDispatcher) {
        // Given
        val sourceUrl = "https://www.mysite.com/"
        val query = "documentation"
        val text = """
            Our documentation is available at:
            - https://www.mysite.com/docs - Main documentation
            - https://external-site.com/docs - External docs (should be filtered)
            - https://another-domain.org/help - More external (should be filtered)
            - https://www.mysite.com/api - API reference
        """.trimIndent()

        // When
        val output = agent.generate(TextLinkDiscoveryInput(text, sourceUrl, query))

        // Then
        output.links.forEach { link ->
            assertTrue(
                link.url.startsWith("https://www.mysite.com/"),
                "All links should be from same domain, but got: ${link.url}"
            )
        }
    }

    @Test
    fun `returns empty when text has no URLs`() = runTest(testCoroutineDispatcher) {
        // Given
        val sourceUrl = "https://www.example.com/"
        val query = "any query"
        val text = "This is plain text without any URLs or links."

        // When
        val output = agent.generate(TextLinkDiscoveryInput(text, sourceUrl, query))

        // Then
        assertTrue(output.links.isEmpty(), "Should return empty list when no URLs in text")
    }

    @Test
    fun `returns empty when only external URLs present`() = runTest(testCoroutineDispatcher) {
        // Given
        val sourceUrl = "https://www.mysite.com/"
        val query = "documentation"
        val text = """
            Check out these external resources:
            - https://external1.com/page
            - https://external2.org/docs
            - https://another.net/help
        """.trimIndent()

        // When
        val output = agent.generate(TextLinkDiscoveryInput(text, sourceUrl, query))

        // Then
        assertTrue(output.links.isEmpty(), "Should return empty list when only external URLs")
    }

    @Test
    fun `handles URLs with trailing punctuation`() = runTest(testCoroutineDispatcher) {
        // Given
        val sourceUrl = "https://www.example.com/"
        val query = "pricing"
        val text = """
            Visit our pricing page (https://www.example.com/pricing).
            Or check the docs at https://www.example.com/docs, which has more info.
        """.trimIndent()

        // When
        val output = agent.generate(TextLinkDiscoveryInput(text, sourceUrl, query))

        // Then
        output.links.forEach { link ->
            assertTrue(!link.url.endsWith(")"), "URL should not end with )")
            assertTrue(!link.url.endsWith(","), "URL should not end with ,")
            assertTrue(!link.url.endsWith("."), "URL should not end with .")
        }
    }
}

