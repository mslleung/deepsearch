package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.IMarkdownConversionAgent
import io.deepsearch.domain.agents.MarkdownConversionInput
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertTrue

class MarkdownConversionAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IMarkdownConversionAgent>()
    private val browserRuntimePool by inject<IBrowserRuntimePool>()

    // Test resources helpers
    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(this::class.java.getResourceAsStream("/$name")) {
            "Missing test resource: $name"
        }.use { it.readBytes() }

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://example.com/",
//            "https://www.otandp.com/body-check/",
//            "https://mybeame.com/beame-student-discount",
//            "https://www.egltours.com/website/home",
            "https://sleekflow.io/pricing"
        ]
    )
    fun `converts webpage to markdown`(url: String) = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            val screenshot = page.takeFullPageScreenshot()
            val html = page.getFullHtml()

            val input = MarkdownConversionInput(
                screenshotBytes = screenshot.bytes,
                html = html
            )

            val output = agent.generate(input)

            println(output.markdown)

            assertTrue(output.markdown.isNotBlank(), "Markdown output should not be blank")
            assertTrue(output.markdown.length > 50, "Markdown should contain substantial content")
        }
    }
}
