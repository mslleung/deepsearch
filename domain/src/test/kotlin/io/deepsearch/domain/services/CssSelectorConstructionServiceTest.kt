package io.deepsearch.domain.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CssSelectorConstructionServiceTest {

    private val service = CssSelectorConstructionService()

    @Test
    fun `should handle truncated HTML with unclosed tags`() {
        // LLM provides truncated HTML snippet (first ~10 lines)
        val truncatedSnippet = """
            <nav id="main-nav" class="navbar">
                <ul class="nav-list">
                    <li><a href="/home">Home</a></li>
                    <li><a href="/about">About</a></li>
        """.trimIndent()

        // This is the cleaned HTML that the LLM saw
        val cleanedHtml = """
            <html>
            <head></head>
            <body>
                <nav id="main-nav" class="navbar">
                    <ul class="nav-list">
                        <li><a href="/home">Home</a></li>
                        <li><a href="/about">About</a></li>
                        <li><a href="/contact">Contact</a></li>
                    </ul>
                </nav>
                <main>
                    <p>Main content</p>
                </main>
            </body>
            </html>
        """.trimIndent()

        // The original full HTML
        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(truncatedSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector for truncated HTML")
        assertTrue(selectors.contains("#main-nav"), "Should find nav by ID")
    }

    @Test
    fun `should handle truncated HTML mid-text content`() {
        val truncatedSnippet = """
            <header class="site-header">
                <div class="logo">My Site</div>
                <nav>
                    <a href="/home">H
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <header class="site-header">
                    <div class="logo">My Site</div>
                    <nav>
                        <a href="/home">Home</a>
                        <a href="/about">About</a>
                    </nav>
                </header>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(truncatedSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector even with mid-text truncation")
        assertTrue(selectors.any { it.contains("site-header") }, "Should find header by class")
    }

    @Test
    fun `should handle truncated HTML mid-attribute`() {
        val truncatedSnippet = """
            <div id="cookie-banner" class="banner cookie-consen
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div id="cookie-banner" class="banner cookie-consent">
                    <p>We use cookies</p>
                    <button>Accept</button>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(truncatedSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector even with mid-attribute truncation")
        assertTrue(selectors.contains("#cookie-banner"), "Should find element by ID")
    }

    @Test
    fun `should return empty list for empty snippet`() {
        val emptySnippet = ""
        val cleanedHtml = "<html><body><div>Content</div></body></html>"
        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(emptySnippet, cleanedHtml, fullHtml)

        assertTrue(selectors.isEmpty(), "Should return empty list for empty snippet")
    }

    @Test
    fun `should return empty list for whitespace-only snippet`() {
        val whitespaceSnippet = "   \n\t  "
        val cleanedHtml = "<html><body><div>Content</div></body></html>"
        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(whitespaceSnippet, cleanedHtml, fullHtml)

        assertTrue(selectors.isEmpty(), "Should return empty list for whitespace-only snippet")
    }

    @Test
    fun `should handle snippet with only opening tag`() {
        val openingTagOnly = """<footer id="page-footer" class="main-footer">"""

        val cleanedHtml = """
            <html>
            <body>
                <footer id="page-footer" class="main-footer">
                    <p>Copyright 2024</p>
                    <nav>
                        <a href="/privacy">Privacy</a>
                    </nav>
                </footer>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(openingTagOnly, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector from opening tag only")
        assertTrue(selectors.contains("#page-footer"), "Should find footer by ID")
    }

    @Test
    fun `should handle complete well-formed HTML snippet`() {
        // Even though we expect truncated HTML, should still work with complete HTML
        val completeSnippet = """
            <div id="sidebar" class="nav-sidebar">
                <ul>
                    <li><a href="/dashboard">Dashboard</a></li>
                    <li><a href="/settings">Settings</a></li>
                </ul>
            </div>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div id="sidebar" class="nav-sidebar">
                    <ul>
                        <li><a href="/dashboard">Dashboard</a></li>
                        <li><a href="/settings">Settings</a></li>
                    </ul>
                </div>
                <main>Content</main>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(completeSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector for complete HTML")
        assertTrue(selectors.contains("#sidebar"), "Should find sidebar by ID")
    }

    @Test
    fun `should distinguish between multiple similar elements using truncated snippet`() {
        val truncatedSnippet = """
            <div class="card">
                <h2>Featured Article</h2>
                <p>This is the featured
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div class="card">
                    <h2>Featured Article</h2>
                    <p>This is the featured article content</p>
                </div>
                <div class="card">
                    <h2>Regular Article</h2>
                    <p>This is a regular article</p>
                </div>
                <div class="card">
                    <h2>Another Article</h2>
                    <p>This is another article</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(truncatedSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector for specific card")
        // Should only match the first card with "Featured Article" and "This is the featured"
        assertEquals(1, selectors.size, "Should distinguish the specific card from others")
    }

    @Test
    fun `should handle malformed HTML gracefully`() {
        val malformedSnippet = """
            <div class="popup"
                <div class="popup-content">
                    <p>Accept cookies?
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div class="popup">
                    <div class="popup-content">
                        <p>Accept cookies?</p>
                        <button>Yes</button>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        // Should not throw exception, but may return empty list
        assertDoesNotThrow {
            service.constructCssSelectorsFromSnippet(malformedSnippet, cleanedHtml, fullHtml)
        }
    }

    @Test
    fun `should handle snippet with nested structure truncation`() {
        val truncatedSnippet = """
            <nav class="breadcrumb">
                <ol>
                    <li><a href="/">Home</a></li>
                    <li><a href="/category">Category</a>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <nav class="breadcrumb">
                    <ol>
                        <li><a href="/">Home</a></li>
                        <li><a href="/category">Category</a></li>
                        <li><a href="/category/sub">Subcategory</a></li>
                    </ol>
                </nav>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(truncatedSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should find selector for nested structure")
        assertTrue(selectors.any { it.contains("breadcrumb") }, "Should find breadcrumb nav")
    }
}

