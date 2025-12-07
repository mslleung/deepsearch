package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.system.measureTimeMillis

class WebpageExtractionServiceBenchmarkTest : KoinTest {

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
            "https://www.otandp.com/body-check/",
//            "https://mybeame.com/beame-student-discount"
        ]
    )
    fun `benchmark webpage extraction performance`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withContext { context ->
            val page = context.newPage()

            page.navigate(url)

            val extractionTime = measureTimeMillis {
                withContext(Dispatchers.IO) {
                    val text = webpageExtractionService.extractWebpage(page, QuerySessionId("test-session-id"))
                    assertTrue(text.markdown.length > 200)
                }
            }

            println("Extraction time for $url: ${extractionTime}ms")
        }
    }
}

