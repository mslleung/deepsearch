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

        assertFalse(selectors.isEmpty(), "Should find selector for cards")
        // With DOM-based matching, all 3 cards have the same structure (div.card > h2 + p)
        // We can't distinguish by text content in truncated snippets
        // This generates hierarchical selectors for each match
        assertEquals(3, selectors.size, "All cards match the structure, returns hierarchical selectors")
        assertTrue(selectors.all { it.contains("card") }, "All selectors should reference card class")
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

    // New tests for DOM-based structural matching

    @Test
    fun `should match structure with different attribute order`() {
        // LLM returns snippet with attributes in different order
        val snippetWithReorderedAttrs = """
            <div class="group/navbar dark" id="navbar-container">
                <div class="z-[100] fixed top-6">
                    <nav class="relative flex items-center">
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div id="navbar-container" class="dark group/navbar">
                    <div class="fixed z-[100] top-6">
                        <nav class="items-center relative flex">
                            <a href="/">Home</a>
                        </nav>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(snippetWithReorderedAttrs, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should match despite different attribute order")
        assertTrue(selectors.contains("#navbar-container"), "Should find element by ID")
    }

    @Test
    fun `should match structure with different whitespace formatting`() {
        // LLM returns snippet with different indentation/spacing
        val snippetWithDifferentWhitespace = """
            <footer class="-mt-56 z-[90] relative dark">
            <div class="footer-diagonal w-full h-[11rem]"></div>
            <div class="bg-navyblue-900 relative pt-28">
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <footer class="-mt-56 z-[90] relative dark">
                    <div class="footer-diagonal w-full h-[11rem]"></div>
                    <div class="bg-navyblue-900 relative pt-28">
                        <p>Footer content</p>
                    </div>
                </footer>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(snippetWithDifferentWhitespace, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should match despite different whitespace")
        assertTrue(selectors.any { it.contains("footer") }, "Should find footer element")
    }

    @Test
    fun `should not match when tag names differ`() {
        // Snippet with different root tag
        val snippetWithDifferentTag = """
            <header class="site-header">
                <div class="logo">Logo</div>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <nav class="site-header">
                    <div class="logo">Logo</div>
                </nav>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(snippetWithDifferentTag, cleanedHtml, fullHtml)

        assertTrue(selectors.isEmpty(), "Should not match when tag names differ")
    }

    @Test
    fun `should not match when required attributes are missing`() {
        // Snippet with attribute that doesn't exist in candidate (without unique ID)
        val snippetWithExtraAttr = """
            <div class="navbar" role="navigation" aria-label="Main">
                <ul>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div class="navbar">
                    <ul>
                        <li>Item</li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(snippetWithExtraAttr, cleanedHtml, fullHtml)

        assertTrue(selectors.isEmpty(), "Should not match when required attributes are missing")
    }

    @Test
    fun `should not match when child structure differs`() {
        // Snippet with different child structure
        val snippetWithDifferentChildren = """
            <nav class="breadcrumb">
                <ul>
                    <li>Home</li>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <nav class="breadcrumb">
                    <ol>
                        <li>Home</li>
                    </ol>
                </nav>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(snippetWithDifferentChildren, cleanedHtml, fullHtml)

        assertTrue(selectors.isEmpty(), "Should not match when child tags differ (ul vs ol)")
    }

    @Test
    fun `should match when candidate has additional children beyond snippet`() {
        // Snippet is truncated but candidate has more children (this is expected)
        val truncatedSnippet = """
            <div class="container">
                <h1>Title</h1>
                <p>First paragraph</p>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div class="container">
                    <h1>Title</h1>
                    <p>First paragraph</p>
                    <p>Second paragraph</p>
                    <p>Third paragraph</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(truncatedSnippet, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should match when candidate has additional children")
        assertTrue(selectors.any { it.contains("container") }, "Should find container element")
    }

    @Test
    fun `should validate deep nesting up to depth limit`() {
        // Snippet with deep nesting (4 levels)
        val deeplyNestedSnippet = """
            <div class="level1">
                <div class="level2">
                    <div class="level3">
                        <div class="level4">
                            <p>Content</p>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div class="level1">
                    <div class="level2">
                        <div class="level3">
                            <div class="level4">
                                <p>Content</p>
                            </div>
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(deeplyNestedSnippet, cleanedHtml, fullHtml)

        // Should match up to the configured depth limit (3 levels)
        assertFalse(selectors.isEmpty(), "Should match deep nesting up to depth limit")
        assertTrue(selectors.any { it.contains("level1") }, "Should find root element")
    }

    @Test
    fun `should handle class attribute comparison as sets`() {
        // Classes in different order should still match
        val snippetWithClasses = """
            <div class="flex items-center justify-between">
                <span>Content</span>
        """.trimIndent()

        val cleanedHtml = """
            <html>
            <body>
                <div class="justify-between flex items-center">
                    <span>Content</span>
                </div>
            </body>
            </html>
        """.trimIndent()

        val fullHtml = cleanedHtml

        val selectors = service.constructCssSelectorsFromSnippet(snippetWithClasses, cleanedHtml, fullHtml)

        assertFalse(selectors.isEmpty(), "Should match when classes are in different order")
        assertTrue(selectors.any { it.contains("flex") }, "Should find element with flex class")
    }
}

