package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

interface IWebpageExtractionService {
    suspend fun extractWebpage(webpage: IBrowserPage): String
}

class WebpageExtractionService(
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val tableInterpretationAgent: ITableInterpretationAgent,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IWebpageExtractionService {

    /**
     * Converts a webpage into text for downstream LLM processing
     */
    override suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        replaceIconsWithTexts(webpage)
        replaceTablesWithTexts(webpage)
        webpage.extractTextContent()
    }

    private suspend fun replaceIconsWithTexts(webpage: IBrowserPage) = coroutineScope {
        val icons = webpage.extractIcons()

        val replacements = icons
            .map { icon ->
                async(ioDispatcher) {
                    val interpretedText = webpageIconInterpretationService.interpretIcon(icon)
                    icon.xPathSelectors.map { xpath -> IBrowserPage.XPathReplacementWithText(xpath, interpretedText) }
                }
            }
            .awaitAll()
            .flatten()

        webpage.replaceElementsByXPathWithText(replacements)
    }

    private suspend fun replaceTablesWithTexts(webpage: IBrowserPage) = coroutineScope {
        // Table processing: identify tables from full-page screenshot and interpret them to markdown
        val fullScreenshot = webpage.takeFullPageScreenshot()
        val tableOutput = tableIdentificationAgent.generate(
            TableIdentificationInput(fullScreenshot.bytes, fullScreenshot.mimeType)
        )

        // Sequentially gather screenshots and HTML for each table (Playwright is not thread-safe)
        val tableInputs = tableOutput.tables.map { table ->
            val elementScreenshot = webpage.getElementScreenshotByXPath(table.xpath)
            val elementHtml = webpage.getElementHtmlByXPath(table.xpath)
            table.xpath to TableInterpretationInput(
                screenshotBytes = elementScreenshot.bytes,
                mimetype = elementScreenshot.mimeType,
                auxiliaryInfo = table.auxiliaryInfo,
                html = elementHtml
            )
        }

        // Interpret tables in parallel (LLM calls can be parallelized)
        val replacements = tableInputs
            .map { (xpath, input) ->
                async(ioDispatcher) {
                    val markdown = tableInterpretationAgent.generate(input).markdown
                    IBrowserPage.XPathReplacementWithText(xpath, markdown)
                }
            }
            .awaitAll()

        webpage.replaceElementsByXPathWithText(replacements)
    }
}