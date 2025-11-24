package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
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
import kotlin.test.assertEquals

class TableIdentificationAgentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val agent by inject<ITableIdentificationAgent>()
    private val browserRuntimePool by inject<IBrowserRuntimePool>()

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://example.com/",
//            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing",
//            "https://mybeame.com/beame-student-discount",
//            "https://www.otandp.com/body-check/online-appointment?selected_medical_package=Standard+Package&__hstc=147241320.301ff111340fc709c07be556781a0457.1761411708083.1761411708083.1761411708083.1&__hssc=147241320.1.1761411708083&__hsfp=1024023444&hsCtaTracking=4952e2f6-6874-4361-895b-b0cf7a596459%7C26dca732-a055-4f46-84dd-7389e2813263"
        ]
    )
    fun `identifies a single table from webpage HTML`(url: String) = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)

            val input = TableIdentificationInput(
                webpage = page
            )
            val output = agent.generate(input)

            assertEquals(1, output.tables.size, "Should identify exactly one table")
            
            // Verify that each CSS selector is unique and specific
            val html = page.getFullHtml()
            output.tables.forEach { table ->
                // Parse the HTML to verify the selector matches exactly one element
                val doc = org.jsoup.Jsoup.parse(html)
                val matchedElements = doc.select(table.cssSelector)
                
                assertEquals(
                    1, 
                    matchedElements.size, 
                    "CSS selector '${table.cssSelector}' should match exactly one element, but matched ${matchedElements.size}"
                )
            }
        }
    }
}
