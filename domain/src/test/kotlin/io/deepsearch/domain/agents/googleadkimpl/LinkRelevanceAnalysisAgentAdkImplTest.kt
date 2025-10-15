package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ILinkRelevanceAnalysisAgent
import io.deepsearch.domain.agents.LinkRelevanceAnalysisInput
import io.deepsearch.domain.browser.IBrowserPool
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

class LinkRelevanceAnalysisAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()
    private val agent by inject<ILinkRelevanceAnalysisAgent>()

    @Test
    fun `finds relevant links in actual webpage HTML`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.example.com/"
        val query = "information about the website"
        
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(url)
        val html = browserPage.getFullHtml()

        // When
        val output = agent.generate(LinkRelevanceAnalysisInput(html, query))

        // Then
        output.links.forEach { link ->
            assertEquals(LinkSource.LINK_RELEVANCE, link.source)
            assertTrue(link.url.isNotBlank(), "URL should not be blank")
            assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
        }
    }

    @Test
    fun `finds relevant links for body check packages on OT&P homepage with direct query`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.otandp.com/"
        val query = "information about body check packages"
        
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(url)
        val html = browserPage.getFullHtml()

        // When
        val output = agent.generate(LinkRelevanceAnalysisInput(html, query))

        // Then
        output.links.forEach { link ->
            assertEquals(LinkSource.LINK_RELEVANCE, link.source)
            assertTrue(link.url.isNotBlank(), "URL should not be blank")
            assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
        }
    }

    @Test
    fun `finds relevant links for body check packages on OT&P homepage with indirect query`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.otandp.com/"
        val query = "how much is Singular Test: VO2 Max"

        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(url)
        val html = browserPage.getFullHtml()

        // When
        val output = agent.generate(LinkRelevanceAnalysisInput(html, query))

        // Then
        // should access https://www.otandp.com/longevity-services
        // answer: $2200
        output.links.forEach { link ->
            assertEquals(LinkSource.LINK_RELEVANCE, link.source)
            assertTrue(link.url.isNotBlank(), "URL should not be blank")
            assertTrue(link.reason.isNotBlank(), "Reason should not be blank")
        }
    }

    @Test
    fun `finds relevant links for body check packages on OT&P homepage with very indirect query`() = runTest(testCoroutineDispatcher) {
        // Given
        val url = "https://www.otandp.com/otandp-digital-app"
        val query = "what are the steps to delete my data on the OT&P Digital App?"

        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(url)
        val html = browserPage.getFullHtml()

        // When
        val output = agent.generate(LinkRelevanceAnalysisInput(html, query))

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
