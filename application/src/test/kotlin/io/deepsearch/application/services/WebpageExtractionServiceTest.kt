package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.domain.browser.IBrowserRuntimePool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class WebpageExtractionServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val browserRuntimePool by inject<IBrowserRuntimePool>()
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val webpageExtractionService by inject<IWebpageExtractionService>()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://mybeame.com/beame-student-discount",
            "https://www.otandp.com/body-check/",
            "https://www.otandp.com/about/history",
            "https://sleekflow.io/pricing",
            "https://sleekflow.io/fair-use-policy"
        ]
    )
    fun `extract webpage text`(url: String) = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()

            page.navigate(url)
            withContext(Dispatchers.IO) {
                val text = webpageExtractionService.extractWebpage(page, "test-session-id")

                assertTrue(text.length > 200)
                println(text)
            }
        }
    }
}
