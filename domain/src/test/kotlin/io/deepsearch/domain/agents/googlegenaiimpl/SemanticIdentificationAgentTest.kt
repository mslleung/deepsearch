package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class SemanticIdentificationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ISemanticIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    @Test
    fun `should handle simple example page`() = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            // Navigate to data URL with the example HTML
            page.navigate("https://www.example.com/")
            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()
            
            val input = SemanticIdentificationInput(
                pageSnapshot = pageSnapshot,
                screenshot = screenshot
            )
            // Should not throw - may or may not identify elements
            // (LLM false positives are acceptable; they're handled downstream)
            val output = agent.generate(input)
            
            // Log what was detected for debugging
            println("Example.com semantic elements: header=${output.elements.header != null}, " +
                    "footer=${output.elements.footer != null}, " +
                    "popups=${output.elements.popups.size}")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://mybeame.com/beame-student-discount",
            "https://www.otandp.com/body-check/",
            "https://www.jetbrains.com/help/exposed/working-with-database.html",
        ]
    )
    fun `should identify navigation elements`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)
            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()

            val input = SemanticIdentificationInput(
                pageSnapshot = pageSnapshot,
                screenshot = screenshot
            )
            val output = agent.generate(input)

            val hasElements = output.elements.header != null ||
                    output.elements.footer != null ||
                    output.elements.navSidebar != null ||
                    output.elements.breadcrumb != null ||
                    output.elements.cookieBanner != null ||
                    output.elements.adBanners.isNotEmpty() ||
                    output.elements.popups.isNotEmpty()
            assertTrue(hasElements, "OT&P webpage should have semantic elements")
        }
    }
}
