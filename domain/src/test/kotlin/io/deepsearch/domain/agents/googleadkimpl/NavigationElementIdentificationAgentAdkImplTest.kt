package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertNotNull

class NavigationElementIdentificationAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    private fun resourceText(name: String): String = resourceBytes(name).toString(Charsets.UTF_8)

    // Loaded resources
    private val exampleHtml: String = resourceText("view-source_https___example.com.html")

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<INavigationElementIdentificationAgent>()
    private val browserRuntimePool by inject<IBrowserRuntimePool>()

    @Test
    fun `should identify no navigation elements on example page`() = runTest(testCoroutineDispatcher) {
        val input = NavigationElementIdentificationInput(
            html = exampleHtml
        )
        val output = agent.generate(input)
        val hasElements = output.elements.header != null ||
                output.elements.footer != null ||
                output.elements.navSidebar != null ||
                output.elements.breadcrumb != null ||
                output.elements.cookieBanner != null ||
                output.elements.adBanners.isNotEmpty() ||
                output.elements.popups.isNotEmpty()
        assertTrue(!hasElements, "Example page should not have navigation elements")
    }

    @Test
    fun `should identify header and footer on OT&P webpage`() = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate("https://www.otandp.com/body-check")

            val input = NavigationElementIdentificationInput(
                html = page.getFullHtml()
            )
            val output = agent.generate(input)
            val hasElements = output.elements.header != null ||
                    output.elements.footer != null ||
                    output.elements.navSidebar != null ||
                    output.elements.breadcrumb != null ||
                    output.elements.cookieBanner != null ||
                    output.elements.adBanners.isNotEmpty() ||
                    output.elements.popups.isNotEmpty()
            assertTrue(hasElements, "Exposed doc webpage should have navigation elements")
        }
    }

    @Test
    fun `should identify header and footer on exposed doc page`() = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate("https://www.jetbrains.com/help/exposed/working-with-database.html")

            val input = NavigationElementIdentificationInput(
                html = page.getFullHtml()
            )
            val output = agent.generate(input)
            val hasElements = output.elements.header != null ||
                    output.elements.footer != null ||
                    output.elements.navSidebar != null ||
                    output.elements.breadcrumb != null ||
                    output.elements.cookieBanner != null ||
                    output.elements.adBanners.isNotEmpty() ||
                    output.elements.popups.isNotEmpty()
            assertTrue(hasElements, "Exposed doc webpage should have navigation elements")
        }
    }
}

