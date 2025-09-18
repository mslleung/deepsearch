package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.agents.PopupIdentificationInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupIdentificationAgentAdkImplTest : KoinTest {

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
    private val exampleScreenshot: ByteArray = resourceBytes("example.com_.png")
    private val exampleHtml: String = resourceText("view-source_https___example.com.html")

    private val otandpBodyCheckScreenshot = Base64.encode(resourceBytes("www.otandp.com_body-check_.png"))
    private val otandpBodyCheckHtml = resourceText("view-source_https___www.otandp.com_body-check_.html")

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IPopupIdentificationAgent>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `no popup should be detected on example`() = runTest(testCoroutineDispatcher) {
        val input = PopupIdentificationInput(
            screenshotBytes = exampleScreenshot,
            mimetype = ImageMimeType.PNG,
            html = exampleHtml
        )
        val output = agent.generate(input)

        assertFalse(
            !output.dismissButtonXPath.isNullOrBlank(),
            "example.com should not have a popup banner in test env"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
    @Test
    fun `popup should be detected on otandp body check page`() = runTest(testCoroutineDispatcher) {
        val input = PopupIdentificationInput(
            screenshotBytes = Base64.decode(otandpBodyCheckScreenshot),
            mimetype = ImageMimeType.PNG,
            html = otandpBodyCheckHtml
        )
        val output = agent.generate(input)

        assertTrue(!output.dismissButtonXPath.isNullOrBlank(), "Expected a dismiss button XPath when popup exists")
    }
}


