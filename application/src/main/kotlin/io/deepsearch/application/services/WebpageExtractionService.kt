package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

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
        val html = webpage.getFullHtml()

        // Step 2: Run LLM operations concurrently using Flow
        val semanticElementsFlow = identifySemanticElementsFlow(html)
        val iconReplacementsFlow = interpretIconsFlow(webpage)
        val imageReplacementsFlow = interpretImagesFlow(webpage)
        val identifiedTablesFlow = identifyTablesFlow(html)

        // Combine all four flows and collect
        data class FlowResults(
            val semanticElements: SemanticElements,
            val iconReplacements: List<IBrowserPage.XPathReplacementWithText>,
            val imageReplacements: List<IBrowserPage.XPathReplacementWithText>,
            val identifiedTables: List<TableIdentification>
        )
        
        val results = combine(
            semanticElementsFlow,
            iconReplacementsFlow,
            imageReplacementsFlow,
            identifiedTablesFlow
        ) { semantic, iconRep, imageRep, tables ->
            FlowResults(semantic, iconRep, imageRep, tables)
        }.first()

        // Step 3: Replace icons and images
        webpage.replaceElementsByXPathWithText(results.iconReplacements + results.imageReplacements)

        // Step 4: Extract popup text (before removal)
        val popupText = extractPopupText(webpage, results.semanticElements)

        // Step 5: Remove semantic elements
        removeSemanticElements(webpage, results.semanticElements)

        // Step 6: Interpret and replace tables (after filtering out removed elements)
        interpretAndReplaceTables(webpage, results.identifiedTables)

        // Step 7: Extract final text and build result
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
        html: String
    ) = flow {
        val duration = measureTimeMillis {
            val semanticElements = semanticIdentificationService.identifySemanticElements(
                html
            )
            emit(semanticElements)
        }
        logger.debug("Semantic element identification took {} ms", duration)
    }

    private fun interpretIconsFlow(webpage: IBrowserPage) = flow {
        val duration = measureTimeMillis {
            val icons = webpage.extractIcons()

            val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons)
            val replacements = icons.zip(interpretedTexts).flatMap { (icon, interpretedText) ->
                icon.xPathSelectors.map { xpath ->
                    IBrowserPage.XPathReplacementWithText(xpath, interpretedText)
                }
            }
            emit(replacements)
        }
        logger.debug("Icon interpretation took {} ms", duration)
    }

    private fun interpretImagesFlow(webpage: IBrowserPage) = flow {
        val duration = measureTimeMillis {
            val images = webpage.extractImages()

            val extractedTexts = webpageImageTextExtractionService.extractTextFromImages(images)
            val replacements = images.zip(extractedTexts).flatMap { (image, extractedText) ->
                image.xPathSelectors.map { xpath ->
                    IBrowserPage.XPathReplacementWithText(xpath, extractedText)
                }
            }
            emit(replacements)
        }
        logger.debug("Image interpretation took {} ms", duration)
    }

    private fun identifyTablesFlow(html: String) = flow {
        val duration = measureTimeMillis {
            val tables = tableIdentificationService.identifyTables(
                TableIdentificationInput(html = html)
            )
            emit(tables)
        }
        logger.debug("Table identification took {} ms", duration)
    }

    private suspend fun extractPopupText(
        webpage: IBrowserPage,
        semanticElements: SemanticElements
    ): String? {
        return if (semanticElements.popups.isNotEmpty()) {
            buildString {
                semanticElements.popups.forEach { popup ->
                    val text = webpage.extractElementTextContentByCssSelector(popup.cssSelector)
                    if (text.isNotBlank()) {
                        appendLine(text)
                    }
                }
            }
        } else {
            null
        }
    }

    private suspend fun interpretAndReplaceTables(
        webpage: IBrowserPage,
        identifiedTables: List<TableIdentification>
    ) {
        val url = webpage.getUrl()
        
        // Filter tables that still exist after semantic element removal
        val existingTables = identifiedTables.filter { table ->
            try {
                webpage.elementExistsByCssSelector(table.cssSelector)
            } catch (e: Exception) {
                logger.debug("Table with CSS selector '{}' was removed, skipping interpretation", 
                    table.cssSelector)
                false
            }
        }
        
        val removedCount = identifiedTables.size - existingTables.size
        if (removedCount > 0) {
            logger.debug("Skipped {} table(s) removed with semantic elements", removedCount)
        }
        
        if (existingTables.isEmpty()) {
            logger.debug("No tables remaining to interpret")
            return
        }
        
        logger.debug("Interpreting {} table(s)", existingTables.size)
        
        // Gather HTML for each existing table
        val duration = measureTimeMillis {
            val tableInputs = existingTables.mapNotNull { table ->
                try {
                    val elementHtml = webpage.getElementHtmlByCssSelector(table.cssSelector)
                    table.cssSelector to TableInterpretationInput(
                        auxiliaryInfo = table.auxiliaryInfo,
                        html = elementHtml
                    )
                } catch (e: Exception) {
                    logger.error("Failed to extract table at URL: {} with CSS selector '{}': {}", 
                        url, table.cssSelector, e.message)
                    null
                }
            }
            
            // Interpret all remaining tables in batch
            val cssSelectors = tableInputs.map { it.first }
            val inputs = tableInputs.map { it.second }
            val markdowns = tableInterpretationService.interpretTablesBatch(inputs)
            
            val replacements = cssSelectors.zip(markdowns).map { (cssSelector, markdown) ->
                IBrowserPage.CssSelectorReplacementWithText(cssSelector, markdown)
            }
            
            webpage.replaceElementsByCssSelectorWithText(replacements)
        }
        logger.debug("Table interpretation and replacement took {} ms", duration)
    }

    private suspend fun removeSemanticElements(
        webpage: IBrowserPage,
        semanticElements: SemanticElements
    ) {
        // Remove each semantic element individually
        semanticElements.header?.let { element ->
            try {
                logger.debug("Removing header via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove header at {}: {}", element.cssSelector, e.message)
            }
        }

        semanticElements.footer?.let { element ->
            try {
                logger.debug("Removing footer via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove footer at {}: {}", element.cssSelector, e.message)
            }
        }

        semanticElements.navSidebar?.let { element ->
            try {
                logger.debug("Removing navigation sidebar via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove navigation sidebar at {}: {}", element.cssSelector, e.message)
            }
        }

        semanticElements.breadcrumb?.let { element ->
            try {
                logger.debug("Removing breadcrumb via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove breadcrumb at {}: {}", element.cssSelector, e.message)
            }
        }

        semanticElements.cookieBanner?.let { element ->
            try {
                logger.debug("Removing cookie banner via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove cookie banner at {}: {}", element.cssSelector, e.message)
            }
        }

        semanticElements.adBanners.forEach { element ->
            try {
                logger.debug("Removing ad banner via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove ad banner at {}: {}", element.cssSelector, e.message)
            }
        }

        semanticElements.popups.forEach { element ->
            try {
                logger.debug("Removing popup via CSS selector: {}", element.cssSelector)
                webpage.removeElementByCssSelector(element.cssSelector)
            } catch (e: Exception) {
                logger.warn("Failed to remove popup at {}: {}", element.cssSelector, e.message)
            }
        }
    }
}