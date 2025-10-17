package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.DispatcherProvider
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IWebpageExtractionService {
    suspend fun extractWebpage(webpage: IBrowserPage): String
}

class WebpageExtractionService(
    private val tableIdentificationService: ITableIdentificationService,
    private val tableInterpretationService: ITableInterpretationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val semanticIdentificationService: ISemanticIdentificationService,
    private val popupContainerIdentificationService: IPopupContainerIdentificationService,
    private val navigationElementRemovalService: INavigationElementRemovalService,
    private val dispatchers: DispatcherProvider
) : IWebpageExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Converts a webpage into text for downstream LLM processing.
     * The extracted text is primed for information retrieval on the current page.
     */
    override suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        // Perform extraction
        val title = webpage.getTitle()
        val description = webpage.getDescription()

        // Identify all semantic elements (navigation + popups) before any modifications
        val semanticElements = semanticIdentificationService.identifySemanticElements(webpage)

        // Extract popup text content first (before removal)
        val popupText = if (semanticElements.popups.isNotEmpty()) {
            buildString {
                semanticElements.popups.forEach { popup ->
                    val text = webpage.extractElementTextContent(popup.xpath)
                    if (text.isNotBlank()) {
                        appendLine(text)
                    }
                }
            }
        } else {
            null
        }

        // Remove all semantic elements (popups + navigation elements)
        removeSemanticElements(webpage, semanticElements)

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
                async(dispatchers.io) {
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

        // Extract text from all images in batch for efficiency
        val extractedTexts = webpageImageTextExtractionService.extractTextFromImages(images)

        // Build replacements for each XPath
        val replacements = images.zip(extractedTexts).flatMap { (image, extractedText) ->
            image.xPathSelectors.map { xpath -> IBrowserPage.XPathReplacementWithText(xpath, extractedText) }
        }

        webpage.replaceElementsByXPathWithText(replacements)
    }

    private suspend fun replaceTablesWithTexts(webpage: IBrowserPage) = coroutineScope {
        // Table processing: identify tables from HTML and interpret them to markdown
        val fullHtml = webpage.getFullHtml()
        val fullScreenshot = webpage.takeFullPageScreenshot()
        val tables = tableIdentificationService.identifyTables(
            TableIdentificationInput(
                screenshotBytes = fullScreenshot.bytes,
                mimetype = fullScreenshot.mimeType,
                html = fullHtml
            )
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
                async(dispatchers.io) {
                    val markdown = tableInterpretationService.interpretTable(input)
                    IBrowserPage.XPathReplacementWithText(xpath, markdown)
                }
            }
            .awaitAll()

        webpage.replaceElementsByXPathWithText(replacements)
    }

    private suspend fun removeSemanticElements(
        webpage: IBrowserPage,
        semanticElements: SemanticElements
    ) {
        // Remove each semantic element individually
        semanticElements.header?.let { element ->
            try {
                logger.debug("Removing header via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove header at {}: {}", element.xpath, e.message)
            }
        }

        semanticElements.footer?.let { element ->
            try {
                logger.debug("Removing footer via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove footer at {}: {}", element.xpath, e.message)
            }
        }

        semanticElements.navSidebar?.let { element ->
            try {
                logger.debug("Removing navigation sidebar via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove navigation sidebar at {}: {}", element.xpath, e.message)
            }
        }

        semanticElements.breadcrumb?.let { element ->
            try {
                logger.debug("Removing breadcrumb via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove breadcrumb at {}: {}", element.xpath, e.message)
            }
        }

        semanticElements.cookieBanner?.let { element ->
            try {
                logger.debug("Removing cookie banner via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove cookie banner at {}: {}", element.xpath, e.message)
            }
        }

        semanticElements.adBanners.forEach { element ->
            try {
                logger.debug("Removing ad banner via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove ad banner at {}: {}", element.xpath, e.message)
            }
        }

        semanticElements.popups.forEach { element ->
            try {
                logger.debug("Removing popup via XPath: {}", element.xpath)
                webpage.removeElement(element.xpath)
            } catch (e: Exception) {
                logger.warn("Failed to remove popup at {}: {}", element.xpath, e.message)
            }
        }
    }
}