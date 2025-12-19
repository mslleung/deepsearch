package io.deepsearch.domain.services

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class JsoupDomServiceTest {

    private val jsoupDomService = JsoupDomService()

    @Test
    fun `extractTextContentWithImagePlaceholders includes placeholder for img with alt text`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Hello world</p>
                <img src="photo.jpg" alt="A beautiful sunset">
                <p>Goodbye</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContentWithImagePlaceholders(doc)

        // Then
        assertContains(result, "Hello world")
        assertContains(result, "<image placeholder alt=\"A beautiful sunset\"/>")
        assertContains(result, "Goodbye")
    }

    @Test
    fun `extractTextContentWithImagePlaceholders includes simple placeholder for img without alt text`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Hello world</p>
                <img src="photo.jpg">
                <p>Goodbye</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContentWithImagePlaceholders(doc)

        // Then
        assertContains(result, "Hello world")
        assertContains(result, "<image placeholder/>")
        assertContains(result, "Goodbye")
    }

    @Test
    fun `extractTextContentWithImagePlaceholders handles empty alt attribute`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Before</p>
                <img src="photo.jpg" alt="">
                <p>After</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContentWithImagePlaceholders(doc)

        // Then
        assertContains(result, "Before")
        assertContains(result, "<image placeholder/>")
        assertContains(result, "After")
    }

    @Test
    fun `extractTextContentWithImagePlaceholders handles whitespace-only alt attribute`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Before</p>
                <img src="photo.jpg" alt="   ">
                <p>After</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContentWithImagePlaceholders(doc)

        // Then
        assertContains(result, "Before")
        assertContains(result, "<image placeholder/>")
        assertContains(result, "After")
    }

    @Test
    fun `extractTextContentWithImagePlaceholders handles multiple images`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Start</p>
                <img src="img1.jpg" alt="First image">
                <p>Middle</p>
                <img src="img2.jpg" alt="Second image">
                <p>End</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContentWithImagePlaceholders(doc)

        // Then
        assertContains(result, "Start")
        assertContains(result, "<image placeholder alt=\"First image\"/>")
        assertContains(result, "Middle")
        assertContains(result, "<image placeholder alt=\"Second image\"/>")
        assertContains(result, "End")
    }

    @Test
    fun `extractTextContent does NOT include image placeholders`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Hello world</p>
                <img src="photo.jpg" alt="A beautiful sunset">
                <p>Goodbye</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContent(doc)

        // Then
        assertContains(result, "Hello world")
        assertContains(result, "Goodbye")
        assertEquals(false, result.contains("<image placeholder"))
    }

    @Test
    fun `extractTextContentWithImagePlaceholders preserves document order`() {
        // Given
        val html = """
            <html>
            <body>
                <p>Line 1</p>
                <img src="img.jpg" alt="Image A">
                <p>Line 2</p>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)

        // When
        val result = jsoupDomService.extractTextContentWithImagePlaceholders(doc)
        val lines = result.lines()

        // Then - verify order is preserved
        val line1Index = lines.indexOfFirst { it.contains("Line 1") }
        val imageIndex = lines.indexOfFirst { it.contains("<image placeholder") }
        val line2Index = lines.indexOfFirst { it.contains("Line 2") }

        assert(line1Index < imageIndex) { "Line 1 should appear before image" }
        assert(imageIndex < line2Index) { "Image should appear before Line 2" }
    }
}

