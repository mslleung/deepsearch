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
        browserPool.withContext { context ->
            val page = context.newPage()

            page.navigate("https://example.com/")
            val title = page.getTitle()

            assertTrue { title.isNotBlank() }
        }
    }

    @Test
    fun `getting description for example webpage`() = runTest(testCoroutineDispatcher) {
        browserPool.withContext { context ->
            val page = context.newPage()

            page.navigate("https://example.com/")
            val description = page.getDescription()

            assertTrue { !description.isNullOrBlank() }
        }
    }

    @Test
    fun `getting icons for example webpage`() = runTest(testCoroutineDispatcher) {
        browserPool.withContext { context ->
            val page = context.newPage()

            page.navigate("https://www.otandp.com/body-check/")
            val icons = page.extractIcons()

            assertTrue { !icons.isEmpty() }
        }
    }

    @Test
    fun `extracting images with CORS fallback`() = runTest(testCoroutineDispatcher) {
        browserPool.withContext { context ->
            val page = context.newPage()

            // Navigate to a page with CORS-blocked images
            page.navigate("https://www.otandp.com/body-check/")
            val images = page.extractImages()

            // Should successfully extract images even with CORS issues
            assertTrue { !images.isEmpty() }

            // Verify all images have valid data
            images.forEach { image ->
                assertTrue { image.bytes.isNotEmpty() }
                assertTrue { image.cssSelectors.isNotEmpty() }
            }
        }
    }
}
