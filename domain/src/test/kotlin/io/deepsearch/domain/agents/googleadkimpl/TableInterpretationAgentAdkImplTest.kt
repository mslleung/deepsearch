package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserRuntimePool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class TableInterpretationAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val tableIdentificationAgent by inject<ITableIdentificationAgent>()
    private val tableInterpretationAgent by inject<ITableInterpretationAgent>()
    private val browserRuntimePool by inject<IBrowserRuntimePool>()

    @ParameterizedTest
    @ValueSource(
        strings = [
//            "https://www.otandp.com/body-check/",
            "https://sleekflow.io/pricing"
        ]
    )
    fun `interprets identified table to markdown`(url: String) = runTest(testCoroutineDispatcher) {
        browserRuntimePool.acquireRuntime { runtime ->
            val browser = runtime.createBrowser()
            val context = browser.createContext()
            val page = context.newPage()
            page.navigate(url)
            val html = page.getFullHtml()
            val idOutput = tableIdentificationAgent.generate(
                TableIdentificationInput(
                    html = html
                )
            )

            idOutput.tables.forEach { table ->
                val md = tableInterpretationAgent.generate(
                    TableInterpretationInput(
                        tableIdentification = table,
                        webpage = page
                    )
                ).markdown

                println(md)

//            assertTrue(md.contains("|"))
//            assertTrue(md.contains("\n"))
            }
        }
    }
}


