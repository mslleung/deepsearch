package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals

class TableIdentificationAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ITableIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://example.com/",
//            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing"
        ]
    )
    fun `identifies a single table from a webpage screenshot`(url: String) = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate(url)
            val screenshot = page.takeFullPageScreenshot()

            val input = TableIdentificationInput(screenshot.bytes, ImageMimeType.PNG)
            val output = agent.generate(input)

            assertEquals(1, output.tables.size, "Should identify exactly one table")
        } finally {
            browser.close()
        }
    }
}
