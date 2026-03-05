package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITableExtractionAgent
import io.deepsearch.domain.agents.TableExtractionInput
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.core.component.inject
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TableExtractionAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ITableExtractionAgent>()

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    @Test
    fun `empty input returns empty output`() = runTest(testCoroutineDispatcher) {
        val output = agent.generate(
            TableExtractionInput(images = emptyList())
        )
        assertTrue(output.extractions.isEmpty(), "Empty input should return empty output")
    }

    @Test
    fun `single image table extraction works correctly`() = runTest(testCoroutineDispatcher) {
        val bytes = resourceBytes("beame_table.webp")
        val output = agent.generate(
            TableExtractionInput(
                images = listOf(
                    TableExtractionInput.ImageItem(
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
    fun `multiple table images extraction works correctly`() = runTest(testCoroutineDispatcher) {
        val images = listOf(
            TableExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            TableExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            )
        )

        val output = agent.generate(TableExtractionInput(images = images))

        assertEquals(2, output.extractions.size, "Should return two extractions")

        // Both should contain markdown tables
        output.extractions.forEachIndexed { index, extraction ->
            assertNotNull(extraction.extractedText, "Extraction at position $index should not be null")
            assertTrue(
                extraction.extractedText!!.contains("|"),
                "Extraction at position $index should contain markdown pipes"
            )
        }
    }

    @Test
    fun `output order matches input order`() = runTest(testCoroutineDispatcher) {
        // Use the same image twice but verify order is maintained
        val images = listOf(
            TableExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            ),
            TableExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            )
        )

        val output = agent.generate(TableExtractionInput(images = images))

        assertEquals(2, output.extractions.size, "Should return two extractions")

        // Both should have table content
        output.extractions.forEach { extraction ->
            assertNotNull(extraction.extractedText, "Extraction should not be null")
            assertTrue(extraction.extractedText!!.contains("|"), "Should contain markdown table")
        }
    }

    @Test
    fun `large batch is processed correctly`() = runTest(testCoroutineDispatcher) {
        // Create a batch of 5 table images
        val images = (1..5).map {
            TableExtractionInput.ImageItem(
                bytes = resourceBytes("beame_table.webp"),
                mimeType = ImageMimeType.WEBP
            )
        }

        val output = agent.generate(TableExtractionInput(images = images))

        assertEquals(5, output.extractions.size, "Should return 5 extractions")

        // All should have markdown table content
        output.extractions.forEachIndexed { index, extraction ->
            assertNotNull(extraction.extractedText, "Extraction at position $index should not be null")
            assertTrue(
                extraction.extractedText!!.contains("|"),
                "Extraction at position $index should contain markdown pipes"
            )
        }
    }
}

