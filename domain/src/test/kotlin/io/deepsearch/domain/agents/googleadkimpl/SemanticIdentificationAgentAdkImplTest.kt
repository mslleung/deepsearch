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
    private val exampleScreenshot: ByteArray = resourceBytes("example.com_.jpg")

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
            mimetype = ImageMimeType.JPEG,
            html = exampleHtml
        )
        val output = agent.generate(input)
        assertTrue(output.elements.isEmpty(), "Simple example page should not have semantic elements")
    }

    @Test
    fun `should identify navigation elements on OT&P webpage`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate("https://www.otandp.com/body-check")

            val screenshot = page.takeFullPageScreenshot()
            val input = SemanticIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = screenshot.mimeType,
                html = page.getFullHtml()
            )
            val output = agent.generate(input)
            
            assertTrue(output.elements.isNotEmpty(), "OT&P webpage should have semantic elements")
        } finally {
            browser.close()
        }
    }

    @Test
    fun `should identify header and footer on documentation page`() = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate("https://www.jetbrains.com/help/exposed/working-with-database.html")

            val screenshot = page.takeFullPageScreenshot()
            val input = SemanticIdentificationInput(
                screenshotBytes = screenshot.bytes,
                mimetype = screenshot.mimeType,
                html = page.getFullHtml()
            )
            val output = agent.generate(input)
            
            assertTrue(output.elements.isNotEmpty(), "Documentation page should have semantic elements")
        } finally {
            browser.close()
        }
    }
}

