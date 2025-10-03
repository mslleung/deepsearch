package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.browser.playwright.PlaywrightBrowserPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes

class PopupDismissServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val browserPool by inject<IBrowserPool>()
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val popupDismissService by inject<IPopupDismissService>()
    private val popupContainerIdentificationService by inject<IPopupContainerIdentificationService>()

    @Test
    fun `dismisses popup on otandp body check page`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()

            // Navigate to the page with popup
            page.navigate("https://www.otandp.com/body-check/")

            // Verify popup exists before dismissal
            val screenshotBefore = page.takeScreenshot()
            val htmlBefore = page.getFullHtml()
            val popupsBefore = popupContainerIdentificationService.identifyPopupContainers(
                screenshotBefore,
                htmlBefore
            )
            
            assertTrue(
                popupsBefore.isNotEmpty(),
                "Expected at least one popup to be detected before dismissal"
            )

            // Dismiss the popup
            popupDismissService.dismissAll(page)

            // Verify popup is removed by checking with Playwright
            // Since IBrowserPage doesn't expose element existence check directly,
            // we verify by checking that the popup identification service no longer finds popups
            val screenshotAfter = page.takeScreenshot()
            val htmlAfter = page.getFullHtml()
            val popupsAfter = popupContainerIdentificationService.identifyPopupContainers(
                screenshotAfter,
                htmlAfter
            )

            assertEquals(
                0,
                popupsAfter.size,
                "Expected no popups to be detected after dismissal"
            )

            // Additionally verify the HTML has changed (popup elements removed)
            assertTrue(
                htmlBefore.length > htmlAfter.length,
                "Expected HTML to be shorter after popup removal"
            )
        } finally {
            browser.close()
        }
    }

    @Test
    fun `dismissAll is idempotent when no popup exists`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        try {
            val context = browser.createContext()
            val page = context.newPage()

            // Navigate to a page without popup
            page.navigate("https://example.com/")

            // Get initial state
            val htmlBefore = page.getFullHtml()

            // Dismiss popups (should be no-op)
            popupDismissService.dismissAll(page)

            // Verify nothing changed
            val htmlAfter = page.getFullHtml()
            assertEquals(htmlBefore, htmlAfter, "HTML should remain unchanged when no popup exists")
        } finally {
            browser.close()
        }
    }
}
