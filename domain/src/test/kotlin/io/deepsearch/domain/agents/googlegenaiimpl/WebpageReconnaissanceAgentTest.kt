package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IWebpageReconnaissanceAgent
import io.deepsearch.domain.agents.WebpageReconnaissanceInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.test.assertTrue

class WebpageReconnaissanceAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<IWebpageReconnaissanceAgent>()
    private val browserPool by inject<IBrowserPool>()

    @Test
    fun `should identify scroll target for health screening query`() = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate("https://www.otandp.com/body-check/")
            page.waitForLoad()

            val pageText = page.extractTextContent()

            val output = agent.generate(
                WebpageReconnaissanceInput(
                    pageText = pageText,
                    query = "Does the standard health screening package cover Stool: FOB Plus Helicobacter Antigen?"
                )
            )

            println("Page structure: ${output.pageStructure}")
            println("Scroll target text: ${output.scrollTargetText} (occurrence ${output.scrollTargetOccurrence})")
            println("Token usage: ${output.tokenUsage}")

            assertTrue(output.pageStructure.isNotBlank(), "Page structure should not be blank")
            assertTrue(output.scrollTargetText != null, "Should identify a scroll target for this specific query")
            assertTrue(output.tokenUsage.totalTokens > 0, "Token usage should be reported")
        }
    }
}
