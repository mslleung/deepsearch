package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.LinkSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkRelevanceAnalysisAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()
    private val agent by inject<ILinkRelevanceAnalysisAgent>()

    @Test
    fun `finds relevant links in actual webpage HTML`() = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            // Given
            val url = "https://www.example.com/"
            val query = "information about the website"

            page.navigate(url)
            val html = page.getFullHtml()

            // When
            val output = agent.generate(LinkRelevanceAnalysisInput(html, query, url))

            // Then
            output.links.forEach { link ->
                assertEquals(LinkSource.LINK_RELEVANCE, link.source)
                assertTrue(link.url.isNotBlank(), "URL should not be blank")
                assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
            }
        }
    }

    @Test
    fun `finds relevant links for body check packages on OT&P homepage with direct query`() =
        runTest(testCoroutineDispatcher) {
            browserPool.withPage { page ->
                // Given
                val url = "https://www.otandp.com/"
                val query = "information about body check packages"

                page.navigate(url)
                val html = page.getFullHtml()

                // When
                val output = agent.generate(LinkRelevanceAnalysisInput(html, query, url))

                // Then
                output.links.forEach { link ->
                    assertEquals(LinkSource.LINK_RELEVANCE, link.source)
                    assertTrue(link.url.isNotBlank(), "URL should not be blank")
                    assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
                }
            }
        }

    @Test
    fun `finds relevant links for body check packages on OT&P homepage with indirect query`() =
        runTest(testCoroutineDispatcher) {
            browserPool.withPage { page ->
                // Given
                val url = "https://www.otandp.com/"
                val query = "how much is Singular Test: VO2 Max"

                page.navigate(url)
                val html = page.getFullHtml()

                // When
                val output = agent.generate(LinkRelevanceAnalysisInput(html, query, url))

                // Then
                // should access https://www.otandp.com/longevity-services
                // answer: $2200
                output.links.forEach { link ->
                    assertEquals(LinkSource.LINK_RELEVANCE, link.source)
                    assertTrue(link.url.isNotBlank(), "URL should not be blank")
                    assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
                }
            }
        }

    @Test
    fun `finds relevant links for body check packages on OT&P homepage with very indirect query`() =
        runTest(testCoroutineDispatcher) {
            browserPool.withPage { page ->
                // Given
                val url = "https://www.otandp.com/otandp-digital-app"
                val query = "what are the steps to delete my data on the OT&P Digital App?"

                page.navigate(url)
                val html = page.getFullHtml()

                // When
                val output = agent.generate(LinkRelevanceAnalysisInput(html, query, url))

                // Then
                // should access https://www.otandp.com/longevity-services
                // answer: $2200
                output.links.forEach { link ->
                    assertEquals(LinkSource.LINK_RELEVANCE, link.source)
                    assertTrue(link.url.isNotBlank(), "URL should not be blank")
                    assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
                }
            }
        }

    @Test
    fun `finds relevant links on Sleekflow homepage with direct query`() =
        runTest(testCoroutineDispatcher) {
            browserPool.withPage { page ->
                // Given
                val url = "https://sleekflow.io/"
                val query = "pricing information"

                page.navigate(url)
                val html = page.getFullHtml()

                // When
                val output = agent.generate(LinkRelevanceAnalysisInput(html, query, url))

                // Then
                output.links.forEach { link ->
                    assertEquals(LinkSource.LINK_RELEVANCE, link.source)
                    assertTrue(link.url.isNotBlank(), "URL should not be blank")
                    assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
                }
            }
        }
}
