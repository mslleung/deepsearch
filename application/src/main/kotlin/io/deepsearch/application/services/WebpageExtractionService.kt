package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.io.encoding.Base64

interface IWebpageExtractionService {
    suspend fun extractWebpage(webpage: IBrowserPage): String
}

class WebpageExtractionService(
    private val tableIdentificationService: ITableIdentificationService,
    private val tableInterpretationService: ITableInterpretationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val popupContainerIdentificationService: IPopupContainerIdentificationService,
    private val navigationElementRemovalService: INavigationElementRemovalService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IWebpageExtractionService {

    /**
     * Converts a webpage into text for downstream LLM processing.
     * The extracted text is primed for information retrieval on the current page.
     */
    override suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        val title = webpage.getTitle()
        val description = webpage.getDescription()

        // Identify popup containers before any modifications
        val popupContainerSelectors = popupContainerIdentificationService.identifyPopupContainers(webpage)

        // Extract popup text content and immediately remove popup containers
        val popupText = if (popupContainerSelectors.isNotEmpty()) {
            buildString {
                popupContainerSelectors.forEach { selector ->
                    val popupText = webpage.extractElementTextContent(selector)
                    appendLine(popupText)
                    webpage.removeElement(selector)
                }
            }
        } else {
            null
        }

        // Remove header and footer navigation elements
        navigationElementRemovalService.removeNavigationElements(webpage)

        replaceIconsWithTexts(webpage)
        replaceImagesWithTexts(webpage)
        replaceTablesWithTexts(webpage)

        val extractedText = webpage.extractTextContent()

        buildString {
            appendLine("URL: ${webpage.getUrl()}")
            appendLine("Title: $title")
            if (!description.isNullOrBlank()) {
                appendLine("Description: $description")
            }
            appendLine()
            if (!popupText.isNullOrBlank()) {
                appendLine(popupText)
            }
            appendLine()
            appendLine(extractedText)
        }.trim()
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

    private suspend fun replaceImagesWithTexts(webpage: IBrowserPage) = coroutineScope {
        val images = webpage.extractImages()

        val replacements = images
            .map { image ->
                async(ioDispatcher) {
                    val extractedText = webpageImageTextExtractionService.extractTextFromImage(image)
                    image.xPathSelectors.map { xpath -> IBrowserPage.XPathReplacementWithText(xpath, extractedText) }
                }
            }
            .awaitAll()
            .flatten()

        webpage.replaceElementsByXPathWithText(replacements)
    }

    private suspend fun replaceTablesWithTexts(webpage: IBrowserPage) = coroutineScope {
        // Table processing: identify tables from HTML and interpret them to markdown
        val fullHtml = webpage.getFullHtml()
        val tables = tableIdentificationService.identifyTables(
            TableIdentificationInput(fullHtml)
        )

        // Sequentially gather screenshots and HTML for each table (Playwright is not thread-safe)
        val tableInputs = tables.map { table ->
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
                    val markdown = tableInterpretationService.interpretTable(input)
                    IBrowserPage.XPathReplacementWithText(xpath, markdown)
                }
            }
            .awaitAll()

        webpage.replaceElementsByXPathWithText(replacements)
    }
}