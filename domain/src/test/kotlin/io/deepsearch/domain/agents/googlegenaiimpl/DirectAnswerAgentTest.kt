package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.DirectAnswerInput
import io.deepsearch.domain.agents.IDirectAnswerAgent
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.models.valueobjects.SearchQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import java.util.stream.Stream
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectAnswerAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IDirectAnswerAgent>()
    private val browserPool by inject<IBrowserPool>()

    companion object {
        @JvmStatic
        fun testCases(): Stream<Arguments> = Stream.of(
            /*            Arguments.of(
                            "https://example.com/",
                            "What is this website about?"
                        ),*/
            Arguments.of(
                "https://www.otandp.com/body-check/",
                "What body check services are available?"
            ),
            /*            Arguments.of(
                            "https://sleekflow.io/",
                            "What is SleekFlow and what does it do?"
                        )*/
        )
    }

    @ParameterizedTest
    @MethodSource("testCases")
    fun `generates direct answer for webpage query`(url: String, query: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)

            val screenshot = page.takeFullPageScreenshot()
            val html = page.getFullHtml()

            val input = DirectAnswerInput(
                screenshotBytes = screenshot.bytes,
                html = html,
                searchQuery = SearchQuery(query, url)
            )

            val output = agent.generate(input)

            assertNotNull(output.answer, "Answer should not be null")
            assertTrue(output.answer.isNotBlank(), "Answer should not be blank")
            assertTrue(output.answer.length > 20, "Answer should contain meaningful content")

            println("Query: $query")
            println("Answer: ${output.answer}")
        }
    }
}

