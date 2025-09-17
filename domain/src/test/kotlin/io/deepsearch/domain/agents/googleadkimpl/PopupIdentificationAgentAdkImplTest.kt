package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.agents.PopupIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupIdentificationAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPopupIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `no popup should be detected on example dot com`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate("https://www.example.com/")
            val screenshot = page.takeScreenshot()

            val input = PopupIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = ImageMimeType.JPEG
            )
            val output = agent.generate(input)

            assertFalse(output.result.exists, "example.com should not have a popup banner in test env")
        } finally {
            browser.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `popup should be detected on otandp body check page`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate("https://www.otandp.com/body-check/")
            val screenshot = page.takeScreenshot()

            val input = PopupIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = ImageMimeType.JPEG
            )
            val output = agent.generate(input)

            assertTrue(output.result.exists, "Expected a popup/cookie banner to exist on OT&P Body Check page")
            assertFalse(output.result.dismissSelector.isNullOrBlank(), "dismissSelector should be non-empty when popup exists")
        } finally {
            browser.close()
        }
    }
}


