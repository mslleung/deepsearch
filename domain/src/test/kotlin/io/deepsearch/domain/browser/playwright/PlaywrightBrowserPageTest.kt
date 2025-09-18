package io.deepsearch.domain.browser.playwright

import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue
import kotlin.test.Test

class PlaywrightBrowserPageTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()

    @Test
    fun `getting title for example webpage`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate("https://example.com/")
        val title = browserPage.getTitle()

        assertTrue { title.isNotBlank() }
    }

    @Test
    fun `getting description for example webpage`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate("https://example.com/")
        val description = browserPage.getDescription()

        assertTrue { !description.isNullOrBlank() }
    }

    @Test
    fun `getting icons for example webpage`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate("https://www.otandp.com/body-check/")
        val icons = browserPage.extractIcons()

        assertTrue { !icons.isEmpty() }
    }
}