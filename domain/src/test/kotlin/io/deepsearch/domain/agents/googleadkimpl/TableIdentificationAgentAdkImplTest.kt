package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
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
    private val browserRuntimePool by inject<IBrowserRuntimePool>()

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://example.com/",
            "https://www.otandp.com/body-check/",
//            "https://sleekflow.io/pricing",
//            "https://mybeame.com/beame-student-discount",
        ]
    )
    fun `identifies a single table from webpage HTML`(url: String) = runTest(testCoroutineDispatcher) {
        val runtime = browserRuntimePool.acquireRuntime()
        try {
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)
            val html = page.getFullHtml()
            val screenshot = page.takeFullPageScreenshot()

            val input = TableIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = screenshot.mimeType,
                html = html
            )
            val output = agent.generate(input)

            assertEquals(1, output.tables.size, "Should identify exactly one table")
        } finally {
            runtime.close()
        }
    }
}
