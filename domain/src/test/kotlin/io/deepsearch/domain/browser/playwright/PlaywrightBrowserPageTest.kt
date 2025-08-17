package io.deepsearch.domain.browser.playwright

import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.services.IBrowserService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue

class PlaywrightBrowserPageTest: KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val browserService by inject<IBrowserService>()

    @Test
    fun `getting page information for simple webpage`() = runTest {
        val browser  = browserService.createBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate("https://example.com/")
        val pageInformation = browserPage.getPageInformation()

        assertTrue(pageInformation.url.contains("example.com"))
        assertTrue(pageInformation.title?.contains("Example", ignoreCase = true) == true)
        assertTrue(pageInformation.headings.isNotEmpty())
        assertTrue(pageInformation.actionSpace.links.isNotEmpty())
        assertTrue(pageInformation.images.isEmpty() || pageInformation.images.all { it.src.isNotBlank() })
    }

    @Test
    fun `action space contains buttons and inputs when present`() = runTest {
        val browser  = browserService.createBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate("https://www.wikipedia.org/")
        val info = browserPage.getPageInformation()

        assertTrue(info.actionSpace.inputs.isNotEmpty())
        assertTrue(info.actionSpace.buttons.isNotEmpty())
        assertTrue(info.actionSpace.links.isNotEmpty())
    }

    @Test
    fun `breadcrumbs if present are captured or empty otherwise`() = runTest {
        val browser  = browserService.createBrowser()
        val browserContext = browser.createContext()
        val browserPage = browserContext.newPage()

        browserPage.navigate("https://example.com/")
        val info = browserPage.getPageInformation()

        assertNotNull(info.breadcrumbs)
    }

}