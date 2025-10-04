package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    private val exampleScreenshot: ByteArray = resourceBytes("example.com_.jpg")
    private val exampleHtml: String = resourceText("view-source_https___example.com.html")

    private val exposedDocScreenshot = Base64.encode(resourceBytes("www.jetbrains.com_help_exposed_working-with-database.jpg"))
    private val exposedDocHtml = resourceText("view-source_https___www.jetbrains.com_help_exposed_working-with-database.html")

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<INavigationElementIdentificationAgent>()

    @Test
    fun `should identify no navigation elements on example page`() = runTest(testCoroutineDispatcher) {
        val input = NavigationElementIdentificationInput(
            screenshotBytes = exampleScreenshot,
            mimetype = ImageMimeType.JPEG,
            html = exampleHtml
        )
        val output = agent.generate(input)

        assertNull(output.headerXPath, "Example page should not have header")
        assertNull(output.footerXPath, "Example page should not have footer")
    }

    @Test
    fun `should identify header and footer on exposed doc page`() = runTest(testCoroutineDispatcher) {
        val input = NavigationElementIdentificationInput(
            screenshotBytes = Base64.decode(exposedDocScreenshot),
            mimetype = ImageMimeType.JPEG,
            html = exposedDocHtml
        )
        val output = agent.generate(input)

        assertNotNull(output.headerXPath, "Exposed doc webpage should have header")
        assertNotNull(output.footerXPath, "Exposed doc webpage should have footer")
    }
}

