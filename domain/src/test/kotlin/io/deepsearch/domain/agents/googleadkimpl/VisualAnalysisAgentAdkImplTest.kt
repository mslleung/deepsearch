package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IVisualAnalysisAgent
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

class VisualAnalysisAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IVisualAnalysisAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `visual analysis returns content for screenshot`() = runTest(testCoroutineDispatcher) {
        // Given
        val query = SearchQuery(
            query = "What is shown on this page?",
            url = "https://www.example.com/"
        )
        val fakeScreenshot = ByteArray(10) { 0x1 }

        // When
        val output = agent.generate(
            IVisualAnalysisAgent.VisualAnalysisInput(
                searchQuery = query,
                screenshotBytes = fakeScreenshot
            )
        )

        // Then
        val result = output.searchResult
        assertTrue(result.content.isNotBlank(), "content should not be blank")
        assertTrue(result.sources.isNotEmpty(), "should include at least one source")
    }
}