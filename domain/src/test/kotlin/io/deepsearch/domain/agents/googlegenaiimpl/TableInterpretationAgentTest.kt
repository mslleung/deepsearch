package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class TableInterpretationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val tableIdentificationAgent by inject<ITableIdentificationAgent>()
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

            // Identify tables using vision + hidden container detection
            val idOutput = tableIdentificationAgent.generate(
                TableIdentificationInput(
                    pageSnapshot = pageSnapshot,
                    screenshot = screenshot
                )
            )

            idOutput.tables.forEach { table ->
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
