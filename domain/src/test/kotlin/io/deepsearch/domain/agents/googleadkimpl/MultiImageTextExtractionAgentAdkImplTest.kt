package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IMultiImageTextExtractionAgent
import io.deepsearch.domain.agents.MultiImageTextExtractionInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.core.component.inject
import org.koin.test.KoinTest
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiImageTextExtractionAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IMultiImageTextExtractionAgent>()

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    @Test
    fun `empty input returns empty output`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            MultiImageTextExtractionInput(images = emptyList())
        )
        assertTrue(output.extractions.isEmpty(), "Empty input should return empty output")
    }

    @Test
    fun `single image text extraction works correctly`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_text.webp")
        val output = agent.generate(
            MultiImageTextExtractionInput(
                images = listOf(
                    MultiImageTextExtractionInput.ImageItem(
                        bytes = bytes,
                        mimeType = ImageMimeType.WEBP
                    )
                )
            )
        )

        assertEquals(1, output.extractions.size, "Should return one extraction")
        assertNotNull(output.extractions[0].extractedText, "Extracted text should not be null for beame_text.webp")
        assertTrue(output.extractions[0].extractedText!!.isNotBlank(), "Extracted text should not be blank for beame_text.webp")
    }

    @Test
    fun `single image table extraction works correctly`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_table.webp")
        val output = agent.generate(
            MultiImageTextExtractionInput(
                images = listOf(
                    MultiImageTextExtractionInput.ImageItem(
                        bytes = bytes,
                        mimeType = ImageMimeType.WEBP
                    )
                )
            )
        )

        assertEquals(1, output.extractions.size, "Should return one extraction")
        assertNotNull(output.extractions[0].extractedText, "Extracted text should not be null for beame_table.webp")
        assertTrue(
            output.extractions[0].extractedText!!.contains("|"),
            "Table extraction should contain markdown pipes for beame_table.webp"
        )
    }

    @Test
    fun `multiple images extraction works correctly`() = runTest(testCoroutineDispatcher) {
        val images = listOf(
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes("beame_text.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes("beame_icon.webp"),
                mimeType = ImageMimeType.WEBP
            )
        )

        val output = agent.generate(MultiImageTextExtractionInput(images = images))

        assertEquals(3, output.extractions.size, "Should return three extractions")

        // Check first image has text
        assertNotNull(output.extractions[0].extractedText, "First extraction should not be null")
        assertTrue(output.extractions[0].extractedText!!.isNotBlank(), "First extraction should not be blank")

        // Check second image has table (markdown pipes)
        assertNotNull(output.extractions[1].extractedText, "Second extraction should not be null")
        assertTrue(output.extractions[1].extractedText!!.contains("|"), "Second extraction should contain table")

        // Third image (icon) may or may not have text - just check it's processed
        // (could be null or empty depending on icon content)
    }

    @Test
    fun `output order matches input order`() = runTest(testCoroutineDispatcher) {
        val images = listOf(
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes("beame_text.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes("beame_icon.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            )
        )

        val output = agent.generate(MultiImageTextExtractionInput(images = images))

        assertEquals(3, output.extractions.size, "Should return three extractions")

        // Position 0: text image should have text
        assertNotNull(output.extractions[0].extractedText, "Position 0 should have text")
        assertTrue(output.extractions[0].extractedText!!.isNotBlank(), "Position 0 should not be blank")

        // Position 2: table image should have table markers
        assertNotNull(output.extractions[2].extractedText, "Position 2 should have text")
        assertTrue(output.extractions[2].extractedText!!.contains("|"), "Position 2 should contain table")
    }

    @Test
    fun `image with no text returns null`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_icon.webp")
        val output = agent.generate(
            MultiImageTextExtractionInput(
                images = listOf(
                    MultiImageTextExtractionInput.ImageItem(
                        bytes = bytes,
                        mimeType = ImageMimeType.WEBP
                    )
                )
            )
        )

        assertEquals(1, output.extractions.size, "Should return one extraction")
        val text = output.extractions[0].extractedText
        // Icon may have null or empty text
        assertTrue(text == null || text.isBlank(), "Icon image should have null or empty text")
    }

    @Test
    fun `large batch is processed correctly`() = runTest(testCoroutineDispatcher) {
        // Create a batch of 10 images (mix of different images)
        val imageFiles = listOf(
            "beame_text.webp", "beame_table.webp", "beame_icon.webp",
            "beame_text.webp", "beame_table.webp", "beame_icon.webp",
            "beame_text.webp", "beame_table.webp", "beame_icon.webp",
            "beame_text.webp"
        )

        val images = imageFiles.map { filename ->
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes(filename),
                mimeType = ImageMimeType.WEBP
            )
        }

        val output = agent.generate(MultiImageTextExtractionInput(images = images))

        assertEquals(10, output.extractions.size, "Should return 10 extractions")

        // Verify most have non-null text (icons may be null)
        val nonNullTexts = output.extractions.count { it.extractedText != null && it.extractedText.isNotBlank() }
        assertEquals(nonNullTexts, 7, "7 images should have non-null text")
    }

    @Test
    fun `extremely large batch is processed correctly`() = runTest(testCoroutineDispatcher) {
        // Create a batch of 56 images to test multi-batch parallel processing
        val imageFiles = mutableListOf<String>()
        repeat(19) {
            imageFiles.add("beame_text.webp")
            imageFiles.add("beame_table.webp")
            imageFiles.add("beame_icon.webp")
        }
        // Add one more to make 57 total
        imageFiles.add("beame_text.webp")

        val images = imageFiles.map { filename ->
            MultiImageTextExtractionInput.ImageItem(
                bytes = resourceBytes(filename),
                mimeType = ImageMimeType.WEBP
            )
        }

        val output = agent.generate(MultiImageTextExtractionInput(images = images))

        assertEquals(57, output.extractions.size, "Should return 57 extractions")

        // Verify most have non-null text
        val nonNullTexts = output.extractions.count { it.extractedText != null && it.extractedText.isNotBlank() }
        assertEquals(nonNullTexts, 38, "38 images should have non-null text (2/3 of total)")
    }
}

