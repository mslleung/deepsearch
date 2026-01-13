package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals

class TableIdentificationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ITableIdentificationAgent>()
    private val browserPool by inject<IBrowserPool>()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.com/",
            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing",
            "https://mybeame.com/beame-student-discount",
            "https://www.otandp.com/body-check/online-appointment?selected_medical_package=Standard+Package&__hstc=147241320.301ff111340fc709c07be556781a0457.1761411708083.1761411708083.1761411708083.1&__hssc=147241320.1.1761411708083&__hsfp=1024023444&hsCtaTracking=4952e2f6-6874-4361-895b-b0cf7a596459%7C26dca732-a055-4f46-84dd-7389e2813263"
        ]
    )
    fun `identifies a single table from webpage HTML`(url: String) = runTest(testCoroutineDispatcher) {
        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val pageSnapshot = page.capturePageSnapshot()
            val screenshot = page.takeFullPageScreenshot()

            // Use vision-based detection with screenshot
            val input = TableIdentificationInput(
                pageSnapshot = pageSnapshot,
                screenshot = screenshot
            )
            val output = agent.generate(input)

            // Vision should identify a reasonable number of tables
            // The old HTML-only approach was finding 57+ tables (fragmenting rows as tables)
            // Vision-based detection should find far fewer - typically 1-10 on a typical page
            assert(output.tables.isNotEmpty()) { "Should identify at least one table" }
            
            // Check that we're not fragmenting the pricing table into 50+ rows like before
            assert(output.tables.size < 15) { 
                "Should not fragment tables into rows (found ${output.tables.size} tables, expected <15)"
            }
            
            println("Vision-based detection found ${output.tables.size} tables:")
            output.tables.forEachIndexed { idx, table ->
                println("  [$idx] ${table.dataId}: ${table.auxiliaryInfo.take(60)}...")
            }
        }
    }
}
