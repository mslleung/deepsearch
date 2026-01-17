package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import io.deepsearch.domain.config.IApplicationCoroutineScope
import java.io.File

class WebpageExtractionServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationBenchmarkTestModule)
    }

    private val browserPool by inject<IBrowserPool>()
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val webpageExtractionService by inject<IWebpageExtractionService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()
    
    @AfterEach
    fun cleanup() {
        // Clean up application scope to cancel background coroutines
        applicationScope.close()
    }

    @Test
    fun `extract rust docs for debugging`() = runTest(testCoroutineDispatcher) {
        val url = "https://doc.rust-lang.org/book/ch01-00-getting-started.html"
        browserPool.withPage { page ->
            page.navigate(url)
            val result = webpageExtractionService.extractWebpage(page, QuerySessionId("rust-debug"))
            
            println("=== RUST DOCS EXTRACTION DEBUG ===")
            println("Markdown length: ${result.markdown.length}")
            println("Title: ${result.title}")
            println("=== FULL MARKDOWN ===")
            println(result.markdown)
            println("=== END MARKDOWN ===")
            
            // Check for expected content
            val hasGettingStarted = result.markdown.contains("Getting Started") || result.markdown.contains("Rust")
            val hasLetsStart = result.markdown.contains("Let's start") || result.markdown.contains("Installing Rust")
            println("Has 'Getting Started': $hasGettingStarted")
            println("Has 'Let's start' or 'Installing Rust': $hasLetsStart")
            
            assertTrue(result.markdown.length > 200, "Markdown should be longer than 200 chars")
            assertTrue(hasGettingStarted || hasLetsStart, "Should contain Rust docs content")
        }
    }

    @Test
    fun `extract wikipedia for debugging imageio issue`() = runTest(testCoroutineDispatcher) {
        val url = "https://en.wikipedia.org/wiki/Artificial_intelligence"
        browserPool.withPage { page ->
            page.navigate(url)
            val result = webpageExtractionService.extractWebpage(page, QuerySessionId("wiki-debug"))
            
            println("=== WIKIPEDIA EXTRACTION DEBUG ===")
            println("Markdown length: ${result.markdown.length}")
            println("Title: ${result.title}")
            println("=== MARKDOWN PREVIEW (first 500 chars) ===")
            println(result.markdown.take(500))
            println("=== END PREVIEW ===")
            
            assertTrue(result.markdown.length > 200, "Markdown should be longer than 200 chars")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Business/SaaS sites
            "https://mybeame.com/beame-student-discount",
            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing",
            
            // Developer/Tech sites
            // 1. GitHub project page (open source)
            "https://github.com/microsoft/vscode",
            // 2. MDN Web Docs (technical documentation)
            "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Introduction",
            // 3. W3Schools (educational site)
            "https://www.w3schools.com/python/python_intro.asp",
            // 4. freeCodeCamp (programming education)
            "https://www.freecodecamp.org/news/",
            // 5. DEV.to (developer community)
            "https://dev.to/",
            // 6. TechCrunch (tech blog)
            "https://techcrunch.com/",
            // 7. Smashing Magazine (web design)
            "https://www.smashingmagazine.com/",
            // 8. Notion help docs (SaaS documentation)
            "https://www.notion.so/help/guides/category/get-started",
            // 9. Stripe documentation (API docs)
            "https://stripe.com/docs/payments",
            // 10. Vercel homepage (developer platform)
            "https://vercel.com/",
            
            // Additional diverse URLs
            // 11. Hacker News - Tech news aggregator (minimal styling, table extraction)
            "https://news.ycombinator.com/",
            // 12. BBC News - International news
            "https://www.bbc.com/news",
            // 13. Airbnb Help - Travel SaaS
            "https://www.airbnb.com/help/article/1320",
            // 14. PyPI package page - Python package registry
            "https://pypi.org/project/requests/",
            // 15. Rust documentation - Programming language docs
            "https://doc.rust-lang.org/book/ch01-00-getting-started.html",
            // 16. Tailwind CSS - Framework documentation
            "https://tailwindcss.com/docs/installation"
        ]
    )
    fun `extract webpage text`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)
            val text = webpageExtractionService.extractWebpage(page, QuerySessionId("test-session-id"))

            assertTrue(text.markdown.length > 200)
            println(text)
        }
    }

}
