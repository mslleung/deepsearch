package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
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

class SemanticIdentificationAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ISemanticIdentificationAgent>()
    private val browserRuntimePool by inject<IBrowserRuntimePool>()

    @Test
    fun `should identify no semantic elements on simple example page`() = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            // Navigate to data URL with the example HTML
            page.navigate("https://www.example.com/")
            
            val input = SemanticIdentificationInput(
                webpage = page
            )
            val output = agent.generate(input)
            val hasElements = output.elements.header != null ||
                    output.elements.footer != null ||
                    output.elements.navSidebar != null ||
                    output.elements.breadcrumb != null ||
                    output.elements.cookieBanner != null ||
                    output.elements.adBanners.isNotEmpty() ||
                    output.elements.popups.isNotEmpty()
            assertTrue(!hasElements, "Simple example page should not have semantic elements")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://mybeame.com/beame-student-discount",
            "https://www.otandp.com/body-check/",
//            "https://www.jetbrains.com/help/exposed/working-with-database.html",
        ]
    )
    fun `should identify navigation elements`(url: String) = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            val input = SemanticIdentificationInput(
                webpage = page
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

