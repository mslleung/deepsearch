package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.domain.browser.IBrowserRuntimePool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
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
//            "https://mybeame.com/beame-student-discount",
//            "https://www.otandp.com/body-check/",
            "https://www.otandp.com/about/history",
        ]
    )
    fun `extract webpage text`(url: String) = runTest(testCoroutineDispatcher) {
        val runtime = browserRuntimePool.acquireRuntime()
        val browser = runtime.createBrowser()
        val context = browser.createContext()
        val page = context.newPage()

        page.navigate(url)
        val text = webpageExtractionService.extractWebpage(page)

        assertTrue(text.length > 200)
    }
}
