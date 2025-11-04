package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    private val dispatchers: IDispatcherProvider
) : IWebpageExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Converts a webpage into text for downstream LLM processing.
     * The extracted text is primed for information retrieval on the current page.
     */
    override suspend fun extractWebpage(webpage: IBrowserPage): String = coroutineScope {
        val title = webpage.getTitle()
        val description = webpage.getDescription()

        // Step 1: Take screenshot and html (browser operations)
        val screenshot = webpage.takeFullPageScreenshot()
        val html = webpage.getFullHtml()

        // Step 2: Extract icons (sequential, browser interaction)
        val icons = webpage.extractIcons()

        // Step 3: Extract images (sequential, browser interaction)
        val images = webpage.extractImages()

        // Step 4: Run LLM operations concurrently using Flow
        val semanticElementsFlow = identifySemanticElementsFlow(
            screenshot.bytes, screenshot.mimeType, html
        )
        val iconReplacementsFlow = interpretIconsFlow(icons)
        val imageReplacementsFlow = interpretImagesFlow(images)

        // Combine all three flows and collect
        val (semanticElements, iconReplacements, imageReplacements) = combine(
            semanticElementsFlow,
            iconReplacementsFlow,
            imageReplacementsFlow
        ) { semantic, iconRep, imageRep ->
            Triple(semantic, iconRep, imageRep)
        }.first()

        // Step 5: Replace icons and images
        webpage.replaceElementsByXPathWithText(iconReplacements + imageReplacements)

        // Step 6: Extract popup text (before removal)
        val popupText = extractPopupText(webpage, semanticElements)

        // Step 7: Remove semantic elements
        removeSemanticElements(webpage, semanticElements)

        // Step 8: Process tables (unchanged)
        replaceTablesWithTexts(webpage)

        // Step 9: Extract final text and build result
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


    private fun identifySemanticElementsFlow(
        screenshotBytes: ByteArray,
        mimeType: ImageMimeType,
        html: String
    ) = flow {
        val semanticElements = semanticIdentificationService.identifySemanticElements(
            screenshotBytes, mimeType, html
        )
        emit(semanticElements)
    }

    private fun interpretIconsFlow(icons: List<IBrowserPage.Icon>) = flow {
        val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons)
        val replacements = icons.zip(interpretedTexts).flatMap { (icon, interpretedText) ->
            icon.xPathSelectors.map { xpath ->
                IBrowserPage.XPathReplacementWithText(xpath, interpretedText)
            }
        }
        emit(replacements)
    }

    private fun interpretImagesFlow(images: List<IBrowserPage.WebImage>) = flow {
        val extractedTexts = webpageImageTextExtractionService.extractTextFromImages(images)
        val replacements = images.zip(extractedTexts).flatMap { (image, extractedText) ->
            image.xPathSelectors.map { xpath ->
                IBrowserPage.XPathReplacementWithText(xpath, extractedText)
            }
        }
        emit(replacements)
    }

    private suspend fun extractPopupText(
        webpage: IBrowserPage,
        semanticElements: SemanticElements
    ): String? {
        return if (semanticElements.popups.isNotEmpty()) {
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
    }

    private suspend fun replaceTablesWithTexts(webpage: IBrowserPage) = coroutineScope {
        // Table processing: identify tables from HTML and interpret them to markdown
        val url = webpage.getUrl()
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
        val tableInputs = tables.mapNotNull { table ->
            try {
                val isVisible = webpage.isElementVisibleByCssSelector(table.cssSelector)
                val elementScreenshot = if (isVisible) {
                    webpage.getElementScreenshotByCssSelector(table.cssSelector)
                } else {
                    null
                }
                val elementHtml = webpage.getElementHtmlByCssSelector(table.cssSelector)
                table.cssSelector to TableInterpretationInput(
                    screenshotBytes = elementScreenshot?.bytes,
                    mimetype = elementScreenshot?.mimeType,
                    auxiliaryInfo = table.auxiliaryInfo,
                    html = elementHtml
                )
            } catch (e: Exception) {
                logger.error("Failed to extract table at URL: {} with CSS selector '{}': {}", 
                    url, table.cssSelector, e.message)
                null
            }
        }

        // Interpret all tables in batch to reduce concurrent upsert issues
        val cssSelectors = tableInputs.map { it.first }
        val inputs = tableInputs.map { it.second }
        val markdowns = tableInterpretationService.interpretTablesBatch(inputs)
        
        val replacements = cssSelectors.zip(markdowns).map { (cssSelector, markdown) ->
            IBrowserPage.CssSelectorReplacementWithText(cssSelector, markdown)
        }

        webpage.replaceElementsByCssSelectorWithText(replacements)
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