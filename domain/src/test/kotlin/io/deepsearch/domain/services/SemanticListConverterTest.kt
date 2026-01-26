package io.deepsearch.domain.services

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticListConverterTest {

    private val converter = SemanticListConverter()

    @Test
    fun `converts simple unordered list`() {
        val html = """
            <ul>
                <li>Item 1</li>
                <li>Item 2</li>
                <li>Item 3</li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertEquals(
            """
            * Item 1
            * Item 2
            * Item 3
            """.trimIndent(),
            markdown
        )
    }

    @Test
    fun `converts simple ordered list`() {
        val html = """
            <ol>
                <li>First</li>
                <li>Second</li>
                <li>Third</li>
            </ol>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertEquals(
            """
            1. First
            2. Second
            3. Third
            """.trimIndent(),
            markdown
        )
    }

    @Test
    fun `converts nested unordered lists`() {
        val html = """
            <ul>
                <li>Parent 1
                    <ul>
                        <li>Child A</li>
                        <li>Child B</li>
                    </ul>
                </li>
                <li>Parent 2</li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("* Parent 1"))
        assertTrue(markdown.contains("  * Child A"))
        assertTrue(markdown.contains("  * Child B"))
        assertTrue(markdown.contains("* Parent 2"))
    }

    @Test
    fun `converts mixed nested lists`() {
        val html = """
            <ul>
                <li>Unordered parent
                    <ol>
                        <li>Ordered child 1</li>
                        <li>Ordered child 2</li>
                    </ol>
                </li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("* Unordered parent"))
        assertTrue(markdown.contains("  1. Ordered child 1"))
        assertTrue(markdown.contains("  2. Ordered child 2"))
    }

    @Test
    fun `handles deeply nested lists`() {
        val html = """
            <ul>
                <li>Level 0
                    <ul>
                        <li>Level 1
                            <ul>
                                <li>Level 2</li>
                            </ul>
                        </li>
                    </ul>
                </li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("* Level 0"))
        assertTrue(markdown.contains("  * Level 1"))
        assertTrue(markdown.contains("    * Level 2"))
    }

    @Test
    fun `handles empty list gracefully`() {
        val html = "<ul></ul>"

        val markdown = converter.convertToMarkdown(html)

        assertEquals("", markdown)
    }

    @Test
    fun `handles no list element`() {
        val html = "<div>Not a list</div>"

        val markdown = converter.convertToMarkdown(html)

        assertEquals("", markdown)
    }

    @Test
    fun `handles multiline item content`() {
        val html = """
            <ul>
                <li>Line 1
Line 2
Line 3</li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("* Line 1 Line 2 Line 3"))
    }

    @Test
    fun `converts real-world feature list`() {
        val html = """
            <ul>
                <li>Help center documentation</li>
                <li>Email support</li>
                <li>24/7 phone support</li>
                <li>Dedicated account manager</li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("* Help center documentation"))
        assertTrue(markdown.contains("* Email support"))
        assertTrue(markdown.contains("* 24/7 phone support"))
        assertTrue(markdown.contains("* Dedicated account manager"))
    }

    @Test
    fun `handles list with empty items`() {
        val html = """
            <ul>
                <li>Item 1</li>
                <li></li>
                <li>Item 3</li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("* Item 1"))
        assertTrue(markdown.contains("*\n") || markdown.contains("*\r"), "Should output marker for empty item")
        assertTrue(markdown.contains("* Item 3"))
    }

    @Test
    fun `handles list item with only nested list`() {
        val html = """
            <ul>
                <li>
                    <ul>
                        <li>Nested only</li>
                    </ul>
                </li>
            </ul>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("  * Nested only"))
    }

    @Test
    fun `preserves order in ordered lists regardless of start attribute`() {
        // Note: We intentionally restart numbering at 1 for simplicity
        val html = """
            <ol start="5">
                <li>Should be 1</li>
                <li>Should be 2</li>
            </ol>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("1. Should be 1"))
        assertTrue(markdown.contains("2. Should be 2"))
    }
}
