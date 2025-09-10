package io.deepsearch.domain.browser.playwright

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue

class PlaywrightBrowserPageTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://example.com/",
            "https://www.otandp.com/body-check/",
//            "https://sleekflow.io/pricing"
        ]
    )
    fun `getting page information for simple webpage`(url: String) = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate(url)
        val pageInformation = browserPage.parse()

        assertTrue(pageInformation.url.contains("example.com"))
        assertTrue(pageInformation.title?.contains("Example", ignoreCase = true) == true)
        assertTrue(pageInformation.description?.contains("Example", ignoreCase = true) == true)
//        assertTrue(pageInformation.textContentForExtraction.contains("Example", ignoreCase = true) == true)
    }

}