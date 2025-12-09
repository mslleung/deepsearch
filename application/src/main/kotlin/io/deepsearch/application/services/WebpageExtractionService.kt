package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Result of webpage extraction containing markdown content and metadata.
 */
data class WebpageExtractionResult(
    val markdown: String,
    val title: String?,
    val description: String?,
    val imageHashes: List<ByteArray> = emptyList() // Image hashes for URL-image linkage tracking
)

interface IWebpageExtractionService {
    suspend fun extractWebpage(webpage: IBrowserPage, sessionId: SessionId): WebpageExtractionResult
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
     * 
     * Optimized flow:
     * 1. Capture page snapshot (HTML, bounding boxes, icons, images) in one call
     * 2. Run parallel operations:
     *    - Semantic identification (uses pre-fetched HTML/bounding boxes)
     *    - Icon interpretation
     *    - Image interpretation
     *    - Table identification (uses pre-fetched HTML/bounding boxes, icon selectors)
     *    - Icon-free table interpretation (runs in parallel, doesn't need icons replaced first)
     * 3. Replace icons and images in DOM
     * 4. Interpret tables WITH icons (after icon replacement)
     * 5. Remove semantic elements (batched)
     * 6. Extract final text
     */
    override suspend fun extractWebpage(webpage: IBrowserPage, sessionId: SessionId): WebpageExtractionResult = coroutineScope {
        val result: WebpageExtractionResult
        val duration = measureTimeMillis {
            val title = webpage.getTitle()
            val description = webpage.getDescription()

            // Step 1: Capture page snapshot with all needed data in one coordinated call
            logger.debug("Capturing page snapshot...")
            val snapshot = webpage.captureSnapshot()
            logger.debug("Page snapshot captured: {} icons, {} images",
                snapshot.mediaExtractionResult.icons.size,
                snapshot.mediaExtractionResult.images.size)
            
            // Step 2: Run LLM operations concurrently using Flow
            // Media-free tables are identified AND interpreted in parallel with other operations
            val semanticElementsFlow = identifySemanticElementsFlow(webpage, sessionId, snapshot)
            val iconReplacementsFlow = interpretIconsFlow(snapshot, sessionId)
            val imageReplacementsFlow = interpretImagesFlow(webpage, snapshot, sessionId)
            val tablesFlow = identifyAndInterpretMediaFreeTablesFlow(webpage, sessionId, snapshot)

            data class FlowResults(
                val semanticElements: SemanticElements,
                val iconReplacements: List<IBrowserPage.CssSelectorReplacementWithText>,
                val imageResults: ImageReplacementsWithHashes,
                val tableResults: TableFlowResult
            )
            
            val results = combine(
                semanticElementsFlow,
                iconReplacementsFlow,
                imageReplacementsFlow,
                tablesFlow
            ) { semantic, iconRep, imageResults, tableResults ->
                FlowResults(semantic, iconRep, imageResults, tableResults)
            }.first()

            logger.debug(
                "Tables: {} interpreted, {} deferred",
                results.tableResults.interpretedReplacements.size,
                results.tableResults.deferredTables.size
            )

            // Step 3: Replace icons and images in DOM
            webpage.replaceElementsByCssSelectorWithText(results.iconReplacements + results.imageResults.replacements)

            // Step 4: Extract popup text (before removal)
            val popupText = extractPopupText(webpage, results.semanticElements)

            // Step 5: Remove semantic elements (batched operation)
            removeSemanticElements(webpage, results.semanticElements)

            // Step 6: Interpret deferred tables (after icon/image replacement)
            val deferredTableReplacements = if (results.tableResults.deferredTables.isNotEmpty()) {
                interpretAndGetReplacements(webpage, results.tableResults.deferredTables, sessionId)
            } else {
                emptyList()
            }

            // Step 7: Replace all tables in DOM
            val allTableReplacements = results.tableResults.interpretedReplacements + deferredTableReplacements
            webpage.replaceElementsByCssSelectorWithText(allTableReplacements)

            // Step 9: Extract final text and build result
            val extractedText = webpage.extractTextContent()
            val markdown = buildString {
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
            
            result = WebpageExtractionResult(
                markdown = markdown,
                title = title,
                description = description,
                imageHashes = results.imageResults.imageHashes
            )
        }
        logger.debug("Webpage extraction took {} ms", duration)
        result
    }

    private fun identifySemanticElementsFlow(
        webpage: IBrowserPage,
        sessionId: SessionId,
        snapshot: IBrowserPage.PageSnapshot
    ) = flow {
        val duration = measureTimeMillis {
            val semanticElements = semanticIdentificationService.identifySemanticElements(
                webpage,
                sessionId,
                snapshot
            )
            emit(semanticElements)
        }
        logger.debug("Semantic element identification took {} ms", duration)
    }

    private fun interpretIconsFlow(
        snapshot: IBrowserPage.PageSnapshot,
        sessionId: SessionId
    ) = flow {
        val icons = snapshot.mediaExtractionResult.icons
        val duration = measureTimeMillis {
            val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons, sessionId)
            val replacements = icons.zip(interpretedTexts).flatMap { (icon, interpretedText) ->
                icon.cssSelectors.map { cssSelector ->
                    IBrowserPage.CssSelectorReplacementWithText(cssSelector, interpretedText)
                }
            }
            logger.debug("Number of icon replacements: {}", replacements.size)
            emit(replacements)
        }
        logger.debug("Icon interpretation took {} ms", duration)
    }

    private data class ImageReplacementsWithHashes(
        val replacements: List<IBrowserPage.CssSelectorReplacementWithText>,
        val imageHashes: List<ByteArray>
    )

    private fun interpretImagesFlow(
        webpage: IBrowserPage,
        snapshot: IBrowserPage.PageSnapshot,
        sessionId: SessionId
    ) = flow {
        val mediaResult = snapshot.mediaExtractionResult
        val duration = measureTimeMillis {
            // Process failed images using screenshot fallback
            val allImages = mediaResult.images.toMutableList()
            for (failedImage in mediaResult.failedImages) {
                if (failedImage.cssSelector.isEmpty()) continue
                try {
                    val isVisible = webpage.isElementVisibleByCssSelector(failedImage.cssSelector)
                    if (!isVisible) continue
                    val screenshot = webpage.getElementScreenshotByCssSelector(failedImage.cssSelector)
                    allImages.add(
                        IBrowserPage.WebImage(
                            bytes = screenshot.bytes,
                            mimeType = screenshot.mimeType,
                            cssSelectors = listOf(failedImage.cssSelector)
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to capture screenshot for image: {}", e.message)
                }
            }

            val extractionResults = webpageImageTextExtractionService.extractTextFromImagesWithHashes(allImages, sessionId)
            val replacements = allImages.zip(extractionResults).flatMap { (image, result) ->
                val wrappedText = wrapImageTextWithXmlTag(result)
                image.cssSelectors.map { cssSelector ->
                    IBrowserPage.CssSelectorReplacementWithText(cssSelector, wrappedText)
                }
            }
            val imageHashes = extractionResults.map { it.imageBytesHash }
            emit(ImageReplacementsWithHashes(replacements, imageHashes))
        }
        logger.debug("Image interpretation took {} ms", duration)
    }

    /**
     * Wraps extracted image text in XML tags with an image ID.
     * Format:
     * - Single line: <image id="img-xxx">interpreted text</image>
     * - Multi-line: <image id="img-xxx">\ninterpreted text\n</image>
     */
    private fun wrapImageTextWithXmlTag(result: ImageExtractionResult): String? {
        val text = result.extractedText ?: return null
        val imageId = result.toImageId()
        
        return if (text.contains('\n')) {
            "<image id=\"$imageId\">\n$text\n</image>"
        } else {
            "<image id=\"$imageId\">$text</image>"
        }
    }

    /**
     * Result of table identification and optional immediate interpretation.
     * 
     * When only media-free tables exist, they are interpreted immediately in the flow
     * and returned in [interpretedReplacements].
     * 
     * When both media-free and media-containing tables exist, ALL tables are deferred
     * to be interpreted together after media replacement, returned in [deferredTables].
     */
    private data class TableFlowResult(
        val interpretedReplacements: List<IBrowserPage.CssSelectorReplacementWithText>,
        val deferredTables: List<TableIdentification>
    )

    /**
     * Identifies tables and optionally interprets media-free tables in one flow.
     * 
     * Optimization strategy:
     * - If ONLY media-free tables exist: interpret them immediately in this flow
     * - If BOTH media-free and media-containing tables exist: defer ALL interpretation
     *   until after media replacement to batch them together
     */
    private fun identifyAndInterpretMediaFreeTablesFlow(
        webpage: IBrowserPage,
        sessionId: SessionId,
        snapshot: IBrowserPage.PageSnapshot
    ) = flow {
        val duration = measureTimeMillis {
            // Step 1: Identify all tables
            val allTables = tableIdentificationService.identifyTables(webpage, sessionId, snapshot)
            
            // Step 2: Partition into media-free and media-containing
            val (tablesWithMedia, tablesWithoutMedia) = allTables.partition { it.containsMedia }
            logger.debug(
                "Table identification: {} total, {} media-free, {} with media",
                allTables.size, tablesWithoutMedia.size, tablesWithMedia.size
            )
            
            // Step 3: Decide interpretation strategy based on table composition
            val result = if (tablesWithMedia.isEmpty()) {
                // Only media-free tables exist: interpret immediately in this flow
                val interpretedReplacements = if (tablesWithoutMedia.isNotEmpty()) {
                    interpretAndGetReplacements(webpage, tablesWithoutMedia, sessionId)
                } else {
                    emptyList()
                }
                TableFlowResult(interpretedReplacements, deferredTables = emptyList())
            } else {
                // Both types exist: defer ALL interpretation until after media replacement
                TableFlowResult(interpretedReplacements = emptyList(), deferredTables = allTables)
            }
            
            emit(result)
        }
        logger.debug("Table identification and media-free interpretation took {} ms", duration)
    }

    /**
     * Interpret tables and return replacements without applying them.
     */
    private suspend fun interpretAndGetReplacements(
        webpage: IBrowserPage,
        tables: List<TableIdentification>,
        sessionId: SessionId
    ): List<IBrowserPage.CssSelectorReplacementWithText> {
        val url = webpage.getUrl()
        
        // Filter tables that still exist
        val existingTables = tables.filter { table ->
            try {
                webpage.elementExistsByCssSelector(table.cssSelector)
            } catch (e: Exception) {
                logger.debug("Table with CSS selector '{}' was removed, skipping interpretation", 
                    table.cssSelector)
                false
            }
        }
        
        if (existingTables.isEmpty()) {
            return emptyList()
        }
        
        val tableInputs = existingTables.mapNotNull { table ->
            try {
                table.cssSelector to TableInterpretationInput(
                    tableIdentification = table,
                    webpage = webpage
                )
            } catch (e: Exception) {
                logger.error("Failed to create input for table at URL: {} with CSS selector '{}': {}", 
                    url, table.cssSelector, e.message)
                null
            }
        }
        
        val cssSelectors = tableInputs.map { it.first }
        val inputs = tableInputs.map { it.second }
        val markdowns = tableInterpretationService.interpretTablesBatch(inputs, sessionId)
        
        return cssSelectors.zip(markdowns).map { (cssSelector, markdown) ->
            IBrowserPage.CssSelectorReplacementWithText(cssSelector, markdown)
        }
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

    private suspend fun removeSemanticElements(
        webpage: IBrowserPage,
        semanticElements: SemanticElements
    ) {
        // Collect all selectors to remove in a single batch operation
        val selectorsToRemove = buildList {
            semanticElements.header?.let { add(it.cssSelector) }
            semanticElements.footer?.let { add(it.cssSelector) }
            semanticElements.navSidebar?.let { add(it.cssSelector) }
            semanticElements.breadcrumb?.let { add(it.cssSelector) }
            semanticElements.cookieBanner?.let { add(it.cssSelector) }
            addAll(semanticElements.adBanners.map { it.cssSelector })
            addAll(semanticElements.popups.map { it.cssSelector })
        }

        if (selectorsToRemove.isEmpty()) {
            logger.debug("No semantic elements to remove")
            return
        }

        logger.debug("Removing {} semantic elements in batch", selectorsToRemove.size)
        webpage.removeElementsByCssSelectors(selectorsToRemove)
    }
}