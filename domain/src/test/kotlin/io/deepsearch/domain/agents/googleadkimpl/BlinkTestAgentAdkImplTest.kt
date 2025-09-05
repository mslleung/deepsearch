package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class BlinkTestAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()
    private val agent by inject<IBlinkTestAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `blink test should pass for clearly related query`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            query = "website purpose",
            url = "https://www.example.com/"
        )
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(searchQuery.url)
        val screenshot = browserPage.takeScreenshot()

        // When
        val output = agent.generate(
            IBlinkTestAgent.BlinkTestInput(
                searchQuery = searchQuery,
                screenshotBytes = screenshot.bytes
            )
        )

        // Then
        assertTrue { output.decision == IBlinkTestAgent.Decision.RELEVANT }
        assertTrue(output.rationale.isNotBlank(), "rationale should not be blank")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `blink test should fail for clearly unrelated query`() = runTest(testCoroutineDispatcher) {

        // Given
        val searchQuery = SearchQuery(
            query = "Who is the men's singles table tennis champion of the 2024 Paris Olympics?",
            url = "https://www.example.com/"
        )
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(searchQuery.url)
        val screenshot = browserPage.takeScreenshot()

        // When
        val output = agent.generate(
            IBlinkTestAgent.BlinkTestInput(
                searchQuery = searchQuery,
                screenshotBytes = screenshot.bytes
            )
        )

        // Then
        assertTrue { output.decision == IBlinkTestAgent.Decision.IRRELEVANT }
        assertTrue(output.rationale.isNotBlank(), "rationale should not be blank")
    }
}