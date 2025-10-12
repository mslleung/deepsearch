package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IImageTextExtractionAgent
import io.deepsearch.domain.agents.ImageTextExtractionInput
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageTextExtractionAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IImageTextExtractionAgent>()

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `extracts text from beame_text image`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_text.webp")
        val output = agent.generate(
            ImageTextExtractionInput(
                bytes = bytes,
                mimeType = ImageMimeType.WEBP
            )
        )

        assertNotNull(output.extractedText, "extractedText should not be null for beame_text.webp")
        assertTrue(output.extractedText.isNotBlank(), "extractedText should not be blank for beame_text.webp")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `extracts text from beame_table image`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_table.webp")
        val output = agent.generate(
            ImageTextExtractionInput(
                bytes = bytes,
                mimeType = ImageMimeType.WEBP
            )
        )

        assertNotNull(output.extractedText, "extractedText should not be null for beame_table.webp")
        assertTrue(output.extractedText.contains("|"), "table extraction should contain markdown pipes for beame_table.webp")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `extracts text or returns null for beame_icon image`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_icon.webp")
        val output = agent.generate(
            ImageTextExtractionInput(
                bytes = bytes,
                mimeType = ImageMimeType.WEBP
            )
        )

        val text = output.extractedText
        assertEquals(text, null, "extractedText should be null for beame_icon.webp")
    }
}


