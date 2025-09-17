package io.deepsearch.application.services

import io.deepsearch.application.config.applicationTestModule
import io.deepsearch.application.searchstrategies.agenticbrowsersearch.IAgenticBrowserSearchStrategy
import io.deepsearch.domain.agents.IIconInterpreterAgent
import io.deepsearch.domain.agents.IconInterpreterInput
import io.deepsearch.domain.agents.IconInterpreterOutput
import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableIdentificationOutput
import io.deepsearch.domain.browser.playwright.PlaywrightBrowser
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.agents.TableInterpretationOutput
import io.deepsearch.domain.repositories.IWebpageIconRepository
import kotlinx.coroutines.CoroutineDispatcher
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.getValue

class WebpageExtractionServiceTest : KoinTest{

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(applicationTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val webpageExtractionService by inject<IWebpageExtractionService>()

    @Test
    fun `extract webpage text for OTandP body check page`() = runTest {
        val browser = PlaywrightBrowser()
        val context = browser.createContext()
        val page = context.newPage()

        page.navigate("https://www.otandp.com/body-check/")
        val text = webpageExtractionService.extractWebpage(page)

        assertTrue(text.contains("Body Check"))
        assertTrue(text.length > 200)

        browser.close()
    }
}


