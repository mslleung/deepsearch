package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
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
    private val browserRuntimePool by inject<IBrowserRuntimePool>()
    private val agent by inject<IBlinkTestAgent>()

    @Test
    fun `blink test should pass for clearly related query`() = runTest(testCoroutineDispatcher) {
        // Given
        val searchQuery = SearchQuery(
            query = "website purpose",
            url = "https://www.example.com/"
        )
        val runtime = browserRuntimePool.acquireRuntime()
        val browser = runtime.createBrowser()
        val context = browser.createContext()
        val page = context.newPage()

        page.navigate(searchQuery.url)
        val screenshot = page.takeScreenshot()

        // When
        val output = agent.generate(
            io.deepsearch.domain.agents.BlinkTestInput(
                searchQuery = searchQuery,
                screenshotBytes = screenshot.bytes
            )
        )

        // Then
        assertTrue { output.decision == IBlinkTestAgent.Decision.RELEVANT }
        assertTrue(output.rationale.isNotBlank(), "rationale should not be blank")
    }

    @Test
    fun `blink test should fail for clearly unrelated query`() = runTest(testCoroutineDispatcher) {

        // Given
        val searchQuery = SearchQuery(
            query = "Who is the men's singles table tennis champion of the 2024 Paris Olympics?",
            url = "https://www.example.com/"
        )
        val runtime = browserRuntimePool.acquireRuntime()
        val browser = runtime.createBrowser()
        val context = browser.createContext()
        val page = context.newPage()

        page.navigate(searchQuery.url)
        val screenshot = page.takeScreenshot()

        // When
        val output = agent.generate(
            io.deepsearch.domain.agents.BlinkTestInput(
                searchQuery = searchQuery,
                screenshotBytes = screenshot.bytes
            )
        )

        // Then
        assertTrue { output.decision == IBlinkTestAgent.Decision.IRRELEVANT }
        assertTrue(output.rationale.isNotBlank(), "rationale should not be blank")
    }
}
