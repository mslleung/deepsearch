package io.deepsearch.domain.services

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTableConverterTest {

    private val converter = SemanticTableConverter()

    @Test
    fun `converts standard table with thead and tbody`() {
        val html = """
            <table>
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Price</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>Product A</td>
                        <td>$10</td>
                    </tr>
                    <tr>
                        <td>Product B</td>
                        <td>$20</td>
                    </tr>
                </tbody>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("| Name | Price |"), "Should have header row")
        assertTrue(markdown.contains("| --- | --- |"), "Should have separator row")
        assertTrue(markdown.contains("| Product A | \$10 |"), "Should have data row 1")
        assertTrue(markdown.contains("| Product B | \$20 |"), "Should have data row 2")
    }

    @Test
    fun `converts table without explicit thead using th elements`() {
        val html = """
            <table>
                <tr>
                    <th>Column 1</th>
                    <th>Column 2</th>
                </tr>
                <tr>
                    <td>Value 1</td>
                    <td>Value 2</td>
                </tr>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("| Column 1 | Column 2 |"), "Should have header row")
        assertTrue(markdown.contains("| --- | --- |"), "Should have separator row")
        assertTrue(markdown.contains("| Value 1 | Value 2 |"), "Should have data row")
    }

    @Test
    fun `handles colspan by adding empty cells`() {
        val html = """
            <table>
                <tr>
                    <th colspan="2">Spanning Header</th>
                </tr>
                <tr>
                    <td>A</td>
                    <td>B</td>
                </tr>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("| Spanning Header |  |"), "Should have spanned header with empty cell")
        assertTrue(markdown.contains("| A | B |"), "Should have data row")
    }

    @Test
    fun `escapes pipe characters in cell content`() {
        val html = """
            <table>
                <tr>
                    <th>Expression</th>
                    <th>Result</th>
                </tr>
                <tr>
                    <td>a | b</td>
                    <td>OR</td>
                </tr>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("a \\| b"), "Should escape pipe characters")
    }

    @Test
    fun `handles empty table gracefully`() {
        val html = "<table></table>"

        val markdown = converter.convertToMarkdown(html)

        assertEquals("", markdown, "Should return empty string for empty table")
    }

    @Test
    fun `handles table with no table element`() {
        val html = "<div>Not a table</div>"

        val markdown = converter.convertToMarkdown(html)

        assertEquals("", markdown, "Should return empty string when no table found")
    }

    @Test
    fun `handles multiline cell content`() {
        val html = """
            <table>
                <tr>
                    <th>Description</th>
                </tr>
                <tr>
                    <td>Line 1
Line 2
Line 3</td>
                </tr>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        // Multiline content should be condensed to single line
        assertTrue(!markdown.contains("\nLine"), "Multiline content should be condensed")
        assertTrue(markdown.contains("Line 1 Line 2 Line 3"), "Content should be space-separated")
    }

    @Test
    fun `handles table with only data rows no header`() {
        val html = """
            <table>
                <tr>
                    <td>A</td>
                    <td>B</td>
                </tr>
                <tr>
                    <td>C</td>
                    <td>D</td>
                </tr>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        // First row should be treated as header
        assertTrue(markdown.contains("| A | B |"), "Should have first row as header")
        assertTrue(markdown.contains("| --- | --- |"), "Should have separator row")
        assertTrue(markdown.contains("| C | D |"), "Should have data row")
    }

    @Test
    fun `handles rows with varying column counts`() {
        val html = """
            <table>
                <tr>
                    <th>A</th>
                    <th>B</th>
                    <th>C</th>
                </tr>
                <tr>
                    <td>1</td>
                    <td>2</td>
                </tr>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        // Should pad short rows
        assertTrue(markdown.contains("| A | B | C |"), "Should have header row")
        assertTrue(markdown.contains("| 1 | 2 |  |"), "Should pad short row with empty cell")
    }

    @Test
    fun `converts real-world pricing table`() {
        val html = """
            <table>
                <thead>
                    <tr>
                        <th>Plan</th>
                        <th>Price</th>
                        <th>Features</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>Basic</td>
                        <td>$9/month</td>
                        <td>5 users, 10GB storage</td>
                    </tr>
                    <tr>
                        <td>Pro</td>
                        <td>$29/month</td>
                        <td>25 users, 100GB storage</td>
                    </tr>
                    <tr>
                        <td>Enterprise</td>
                        <td>Contact us</td>
                        <td>Unlimited users, custom storage</td>
                    </tr>
                </tbody>
            </table>
        """.trimIndent()

        val markdown = converter.convertToMarkdown(html)

        assertTrue(markdown.contains("| Plan | Price | Features |"), "Should have header")
        assertTrue(markdown.contains("Basic"), "Should have Basic plan")
        assertTrue(markdown.contains("Enterprise"), "Should have Enterprise plan")
        assertTrue(markdown.contains("Contact us"), "Should have contact text")
    }
}
