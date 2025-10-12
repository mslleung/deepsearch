package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
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

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    private fun resourceText(name: String): String = resourceBytes(name).toString(Charsets.UTF_8)

    // Loaded resources
    private val exampleHtml: String = resourceText("view-source_https___example.com.html")
    private val exampleScreenshot: ByteArray = resourceBytes("example.com_.webp")

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ISemanticIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    @Test
    fun `should identify no semantic elements on simple example page`() = runTest(testCoroutineDispatcher) {
        // Verify resources are loaded
        assertTrue(exampleScreenshot.isNotEmpty(), "Screenshot should be loaded")
        assertTrue(exampleHtml.isNotBlank(), "HTML should be loaded")
        
        val input = SemanticIdentificationInput(
            screenshotBytes = exampleScreenshot,
            mimetype = ImageMimeType.WEBP,
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
        assertTrue(!hasElements, "Simple example page should not have semantic elements")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://mybeame.com/beame-student-discount",
//            "https://www.otandp.com/body-check/",
//            "https://www.jetbrains.com/help/exposed/working-with-database.html",
        ]
    )
    fun `should identify navigation elements`(url: String) = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate(url)

            val screenshot = page.takeFullPageScreenshot()
            val input = SemanticIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = screenshot.mimeType,
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
            assertTrue(hasElements, "OT&P webpage should have semantic elements")
        } finally {
            browser.close()
        }
    }
}

