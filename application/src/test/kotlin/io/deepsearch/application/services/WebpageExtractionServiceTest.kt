package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.application.config.testModule
import io.deepsearch.domain.browser.playwright.PlaywrightBrowser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class WebpageExtractionServiceTest : KoinTest{

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule, testModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val webpageExtractionService by inject<IWebpageExtractionService>()

    @Test
    fun `extract webpage text for OTandP body check page`() = runTest(testCoroutineDispatcher) {
        val browser = PlaywrightBrowser()
        val context = browser.createContext()
        val page = context.newPage()

        page.navigate("https://www.otandp.com/body-check/")
        val text = webpageExtractionService.extractWebpage(page)

        assertTrue(text.contains("Body Check"))
        assertTrue(text.length > 200)

        browser.close()
    }
}


