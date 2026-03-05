package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.IVisualIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.VisualIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest

class TableInterpretationAgentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val visualIdentificationAgent by inject<IVisualIdentificationAgent>()
    private val tableInterpretationAgent by inject<ITableInterpretationAgent>()
    private val boundingBoxDerivationService by inject<IBoundingBoxDerivationService>()
    private val browserPool by inject<IBrowserPool>()

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing"
        ]
    )
    fun `interprets identified table to markdown`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)
            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()

            // Identify tables using combined visual identification
            val visualOutput = visualIdentificationAgent.generate(
                VisualIdentificationInput(
                    pageSnapshot = pageSnapshot,
                    screenshot = screenshot
                )
            )

            visualOutput.tables.forEach { table ->
                // Derive bounding boxes from page snapshot
                val derivedData = boundingBoxDerivationService.deriveElementBoundingBoxes(
                    cssSelector = table.cssSelector,
                    html = pageSnapshot.html,
                    pageBoundingBoxes = pageSnapshot.boundingBoxes
                )

                // Get table HTML from page (in real usage, this comes from Jsoup after media replacement)
                val tableHtml = page.getElementHtmlByCssSelector(table.cssSelector)

                val md = tableInterpretationAgent.generate(
                    TableInterpretationInput(
                        tableIdentification = table,
                        tableHtml = tableHtml,
                        boundingBoxes = derivedData?.boundingBoxes ?: emptyMap()
                    )
                ).markdown

                println(md)

//            assertTrue(md.contains("|"))
//            assertTrue(md.contains("\n"))
            }
        }
    }
}
