package io.deepsearch.domain.agents.googleadkimpl

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.constants.ImageMimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.io.encoding.Base64

class TableInterpretationAgentAdkImplTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

//    private val tableInterpretationInput1 = TableInterpretationInput(
//        screenshotBytes = elementShot.bytes,
//        mimetype = ImageMimeType.JPEG,
//        html = elementHtml,
//        auxiliaryInfo = first.auxiliaryInfo)

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val tableIdentificationAgent by inject<ITableIdentificationAgent>()
    private val tableInterpretationAgent by inject<ITableInterpretationAgent>()
    private val browserPool by inject<IBrowserPool>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.otandp.com/body-check/"
        ]
    )
    fun `interprets identified table to markdown`(url: String) = runTest(testCoroutineDispatcher) {
        val browser = browserPool.acquireBrowser()
        val context = browser.createContext()
        try {
            val page = context.newPage()
            page.navigate(url)
            val full = page.takeFullPageScreenshot()
            val idOutput = tableIdentificationAgent.generate(
                TableIdentificationInput(full.bytes, ImageMimeType.JPEG)
            )
            // Take first table if any
            val first = idOutput.tables.firstOrNull() ?: return@runTest
            val elementShot = page.getElementScreenshotByXPath(first.xpath)
            val elementHtml = page.getElementHtmlByXPath(first.xpath)

            val base64 = Base64.encode(elementShot.bytes)

            val md = tableInterpretationAgent.generate(
                TableInterpretationInput(
                    screenshotBytes = elementShot.bytes,
                    mimetype = elementShot.mimeType,
                    html = elementHtml,
                    auxiliaryInfo = first.auxiliaryInfo
                )
            ).markdown

            assertTrue(md.contains("|"))
            assertTrue(md.contains("\n"))
        } finally {
            browser.close()
        }
    }
}


