package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://mybeame.com/beame-student-discount",
            "https://www.otandp.com/body-check/",
            "https://www.otandp.com/about/history",
            "https://sleekflow.io/pricing",
            "https://sleekflow.io/fair-use-policy",
            "https://sleekflow.io/ticketing",
            // 1. GitHub project page (open source)
            "https://github.com/microsoft/vscode",
            // 2. MDN Web Docs (technical documentation)
            "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Introduction",
            // 3. W3Schools (educational site - good extraction)
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
            "https://vercel.com/"
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
