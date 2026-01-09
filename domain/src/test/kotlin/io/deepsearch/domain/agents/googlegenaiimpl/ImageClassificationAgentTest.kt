package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IImageClassificationAgent
import io.deepsearch.domain.agents.ImageClassificationInput
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageClassificationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IImageClassificationAgent>()

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    @Test
    fun `empty input returns empty output`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            ImageClassificationInput(images = emptyList())
        )
        assertTrue(output.classifications.isEmpty(), "Empty input should return empty output")
    }

    @Test
    fun `single image text extraction works correctly`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_text.webp")
        val output = agent.generate(
            ImageClassificationInput(
                images = listOf(
                    ImageClassificationInput.ImageItem(
                        bytes = bytes,
                        mimeType = ImageMimeType.WEBP
                    )
                )
            )
        )

        assertEquals(1, output.classifications.size, "Should return one classification")
        assertTrue(
            output.classifications[0].imageType.isNotBlank(),
            "Image type should be a non-blank string"
        )
        assertNotNull(output.classifications[0].imageDescription, "Image description should not be null for beame_text.webp")
        assertTrue(output.classifications[0].imageDescription!!.isNotBlank(), "Image description should not be blank for beame_text.webp")
    }

    @Test
    fun `single image table detection works correctly`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_table.webp")
        val output = agent.generate(
            ImageClassificationInput(
                images = listOf(
                    ImageClassificationInput.ImageItem(
                        bytes = bytes,
                        mimeType = ImageMimeType.WEBP
                    )
                )
            )
        )

        assertEquals(1, output.classifications.size, "Should return one classification")
        assertTrue(output.classifications[0].needsTableInterpretation, "Table image should have needsTableInterpretation=true")
    }

    @Test
    fun `multiple images classification works correctly`() = runTest(testCoroutineDispatcher) {
        val images = listOf(
            ImageClassificationInput.ImageItem(
                bytes = resourceBytes("beame_text.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            ImageClassificationInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            ImageClassificationInput.ImageItem(
                bytes = resourceBytes("beame_icon.webp"),
                mimeType = ImageMimeType.WEBP
            )
        )

        val output = agent.generate(ImageClassificationInput(images = images))

        assertEquals(3, output.classifications.size, "Should return three classifications")

        // Check first image has description
        assertNotNull(output.classifications[0].imageDescription, "First classification should have description")
        assertTrue(output.classifications[0].imageDescription!!.isNotBlank(), "First classification description should not be blank")

        // Check second image detects table
        assertTrue(output.classifications[1].needsTableInterpretation, "Second image should have needsTableInterpretation=true")

        // Third image (icon) should not need table interpretation
        assertFalse(output.classifications[2].needsTableInterpretation, "Icon should have needsTableInterpretation=false")
    }

    @Test
    fun `output order matches input order`() = runTest(testCoroutineDispatcher) {
        val images = listOf(
            ImageClassificationInput.ImageItem(
                bytes = resourceBytes("beame_text.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            ImageClassificationInput.ImageItem(
                bytes = resourceBytes("beame_icon.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            ImageClassificationInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            )
        )

        val output = agent.generate(ImageClassificationInput(images = images))

        assertEquals(3, output.classifications.size, "Should return three classifications")

        // Position 0: text image should have description
        assertNotNull(output.classifications[0].imageDescription, "Position 0 should have description")
        assertTrue(output.classifications[0].imageDescription!!.isNotBlank(), "Position 0 description should not be blank")

        // Position 2: table image should have needsTableInterpretation=true
        assertTrue(output.classifications[2].needsTableInterpretation, "Position 2 should have needsTableInterpretation=true")
    }

    @Test
    fun `icon image returns appropriate type and description`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_icon.webp")
        val output = agent.generate(
            ImageClassificationInput(
                images = listOf(
                    ImageClassificationInput.ImageItem(
                        bytes = bytes,
                        mimeType = ImageMimeType.WEBP
                    )
                )
            )
        )

        assertEquals(1, output.classifications.size, "Should return one classification")
        assertTrue(
            output.classifications[0].imageType.isNotBlank(),
            "Icon should have a non-blank image type"
        )
        assertFalse(output.classifications[0].needsTableInterpretation, "Icon should not need table interpretation")
    }
}
