package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import io.deepsearch.domain.testing.IsolatedKoinTest
import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebpageIndexingServiceTest : IsolatedKoinTest() {

    private lateinit var koinApp: KoinApplication
    private val browserPool by inject<IBrowserPool>()
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val webpageIndexingService by inject<IWebpageIndexingService>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    @BeforeAll
    fun setup() {
        koinApp = koinApplication {
            modules(applicationBenchmarkTestModule)
        }
        koinApp.createEagerInstances()
        testKoin = koinApp.koin
    }

    @AfterAll
    fun teardown() {
        applicationScope.close()
        koinApp.close()
    }

    @Test
    fun `index rust docs for comparison with extraction`() = runTest(testCoroutineDispatcher) {
        val url = "https://doc.rust-lang.org/book/ch01-00-getting-started.html"
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val result = webpageIndexingService.indexWebpage(page, QuerySessionId("rust-index"))

            println("=== RUST DOCS INDEXING ===")
            println("Markdown length: ${result.markdown.length}")
            println("Title: ${result.title}")
            println("=== FULL MARKDOWN ===")
            println(result.markdown)
            println("=== END MARKDOWN ===")

            assertTrue(result.markdown.length > 200, "Markdown should be longer than 200 chars")
            assertTrue(
                result.markdown.contains("Getting Started") || result.markdown.contains("Rust"),
                "Should contain Rust docs content"
            )
        }
    }

    @Test
    fun `index wikipedia for comparison with extraction`() = runTest(testCoroutineDispatcher) {
        val url = "https://en.wikipedia.org/wiki/Artificial_intelligence"
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val result = webpageIndexingService.indexWebpage(page, QuerySessionId("wiki-index"))

            println("=== WIKIPEDIA INDEXING ===")
            println("Markdown length: ${result.markdown.length}")
            println("Title: ${result.title}")
            println("=== MARKDOWN PREVIEW (first 500 chars) ===")
            println(result.markdown.take(500))
            println("=== END PREVIEW ===")

            assertTrue(result.markdown.length > 200, "Markdown should be longer than 200 chars")
        }
    }

    @Test
    fun `index OT&P body check for comparison with extraction`() = runTest(testCoroutineDispatcher) {
        val url = "https://www.otandp.com/body-check/"
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val result = webpageIndexingService.indexWebpage(page, QuerySessionId("otp-index"))

            println("=== OT&P BODY CHECK INDEXING ===")
            println("Markdown length: ${result.markdown.length}")
            println("Title: ${result.title}")
            println("=== FULL MARKDOWN ===")
            println(result.markdown)
            println("=== END MARKDOWN ===")

            assertTrue(result.markdown.length > 200, "Markdown should be longer than 200 chars")
        }
    }

    @Test
    fun `index SleekFlow pricing for comparison with extraction`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        val url = "https://sleekflow.io/pricing"
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val result = webpageIndexingService.indexWebpage(page, QuerySessionId("sleekflow-index"))

            println("=== SLEEKFLOW PRICING INDEXING ===")
            println("Markdown length: ${result.markdown.length}")
            println("Title: ${result.title}")
            println("=== FULL MARKDOWN ===")
            println(result.markdown)
            println("=== END MARKDOWN ===")

            assertTrue(result.markdown.length > 200, "Markdown should be longer than 200 chars")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing",
        ]
    )
    fun `index webpage`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val result = webpageIndexingService.indexWebpage(page, QuerySessionId("test-session-id"))

            assertTrue(result.markdown.length > 200)
            println(result)
        }
    }
}
