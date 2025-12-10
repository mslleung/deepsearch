package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.SessionId
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
     * Optimized flow - each operation starts as soon as its input is ready:
     * 
     * 1. Three parallel pipelines that each start LLM work immediately when browser data arrives:
     *    - Pipeline A: capturePageSnapshot() -> semantic identification + table identification (fast)
     *    - Pipeline B: extractIcons() -> icon interpretation (slower)
     *    - Pipeline C: extractImages() -> image interpretation (slower)
     *    
     *    Page snapshot is fast, so semantic/table identification start quickly.
     *    Icon/image extraction are slower, but their interpretation starts as soon as they're ready.
     *    Table identification detects media by tag/class, so it doesn't wait for media extraction.
     *    
     * 2. After all pipelines complete:
     *    - Replace icons and images in DOM
     *    - Interpret tables WITH media (after icon/image replacement)
     *    - Remove semantic elements
     *    - Extract final text
     */
    override suspend fun extractWebpage(webpage: IBrowserPage, sessionId: SessionId): WebpageExtractionResult =
        coroutineScope {
            val result: WebpageExtractionResult
            val duration = measureTimeMillis {
                // Step 1: Start all browser operations in parallel, then start LLM operations
                // as soon as their inputs are ready (don't wait for slower operations)
                // - Page snapshot is fast -> immediately feeds semantic/table identification
                // - Icon extraction is slower -> feeds icon interpretation when ready
                // - Image extraction is slower -> feeds image interpretation when ready
                logger.debug("Starting browser operations in parallel...")
                
                // Create flows that chain browser call -> LLM processing
                // Each flow starts its LLM operation as soon as its browser data is ready
                val semanticAndTablesFlow = flow {
                    val pageSnapshot = webpage.capturePageSnapshot()
                    logger.debug("Page snapshot captured, starting semantic/table identification...")
                    
                    // Run semantic and table identification in parallel (both use pageSnapshot)
                    val semanticFlow = flow { emit(identifySemanticElements(webpage, sessionId, pageSnapshot)) }
                    val tablesFlow = flow { emit(identifyAndInterpretMediaFreeTables(webpage, sessionId, pageSnapshot)) }
                    
                    val combined = combine(semanticFlow, tablesFlow) { semantic, tables ->
                        semantic to tables
                    }.first()
                    
                    emit(SemanticAndTablesResult(pageSnapshot, combined.first, combined.second))
                }
                
                val iconReplacementsFlow = flow {
                    val icons = webpage.extractIcons()
                    logger.debug("Icons extracted ({}), starting interpretation...", icons.size)
                    emit(interpretIcons(icons, sessionId))
                }
                
                val imageReplacementsFlow = flow {
                    val images = webpage.extractImages()
                    logger.debug("Images extracted ({}), starting interpretation...", images.size)
                    emit(interpretImages(images, sessionId))
                }
                
                // All flows run in parallel - each starts its LLM work as soon as browser data is ready
                val results = combine(
                    semanticAndTablesFlow,
                    iconReplacementsFlow,
                    imageReplacementsFlow
                ) { semanticAndTables, iconRep, imageResults ->
                    FlowResults(
                        pageSnapshot = semanticAndTables.pageSnapshot,
                        semanticElements = semanticAndTables.semanticElements,
                        tableResults = semanticAndTables.tableResults,
                        iconReplacements = iconRep,
                        imageResults = imageResults
                    )
                }.first()
                
                val title = results.pageSnapshot.title
                val description = results.pageSnapshot.description
                val url = results.pageSnapshot.url
                
                logger.debug(
                    "All operations complete. Tables: {} interpreted, {} deferred",
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
                    appendLine("URL: $url")
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

    private suspend fun identifySemanticElements(
        webpage: IBrowserPage,
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): SemanticElements {
        val result: SemanticElements
        val duration = measureTimeMillis {
            result = semanticIdentificationService.identifySemanticElements(
                webpage,
                sessionId,
                pageSnapshot
            )
        }
        logger.debug("Semantic element identification took {} ms", duration)
        return result
    }

    private suspend fun interpretIcons(
        icons: List<IBrowserPage.Icon>,
        sessionId: SessionId
    ): List<IBrowserPage.CssSelectorReplacementWithText> {
        val replacements: List<IBrowserPage.CssSelectorReplacementWithText>
        val duration = measureTimeMillis {
            val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons, sessionId)
            replacements = icons.zip(interpretedTexts).flatMap { (icon, interpretedText) ->
                icon.cssSelectors.map { cssSelector ->
                    IBrowserPage.CssSelectorReplacementWithText(cssSelector, interpretedText)
                }
            }
            logger.debug("Number of icon replacements: {}", replacements.size)
        }
        logger.debug("Icon interpretation took {} ms", duration)
        return replacements
    }

    private data class ImageReplacementsWithHashes(
        val replacements: List<IBrowserPage.CssSelectorReplacementWithText>,
        val imageHashes: List<ByteArray>
    )

    private data class SemanticAndTablesResult(
        val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
        val semanticElements: SemanticElements,
        val tableResults: TableFlowResult
    )

    private data class FlowResults(
        val pageSnapshot: IBrowserPage.PageSnapshotWithMetadata,
        val semanticElements: SemanticElements,
        val tableResults: TableFlowResult,
        val iconReplacements: List<IBrowserPage.CssSelectorReplacementWithText>,
        val imageResults: ImageReplacementsWithHashes
    )

    private suspend fun interpretImages(
        images: List<IBrowserPage.WebImage>,
        sessionId: SessionId
    ): ImageReplacementsWithHashes {
        val result: ImageReplacementsWithHashes
        val duration = measureTimeMillis {
            // Images already include screenshot fallbacks (handled internally by browser)
            val extractionResults =
                webpageImageTextExtractionService.extractTextFromImagesWithHashes(images, sessionId)
            val replacements = images.zip(extractionResults).flatMap { (image, extractionResult) ->
                val wrappedText = wrapImageTextWithXmlTag(extractionResult)
                image.cssSelectors.map { cssSelector ->
                    IBrowserPage.CssSelectorReplacementWithText(cssSelector, wrappedText)
                }
            }
            val imageHashes = extractionResults.map { it.imageBytesHash }
            result = ImageReplacementsWithHashes(replacements, imageHashes)
        }
        logger.debug("Image interpretation took {} ms", duration)
        return result
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
     * - If ONLY media-free tables exist: interpret them immediately
     * - If BOTH media-free and media-containing tables exist: defer ALL interpretation
     *   until after media replacement to batch them together
     */
    private suspend fun identifyAndInterpretMediaFreeTables(
        webpage: IBrowserPage,
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): TableFlowResult {
        val result: TableFlowResult
        val duration = measureTimeMillis {
            // Step 1: Identify all tables
            val allTables = tableIdentificationService.identifyTables(webpage, sessionId, pageSnapshot)

            // Step 2: Partition into media-free and media-containing
            val (tablesWithMedia, tablesWithoutMedia) = allTables.partition { it.containsMedia }
            logger.debug(
                "Table identification: {} total, {} media-free, {} with media",
                allTables.size, tablesWithoutMedia.size, tablesWithMedia.size
            )

            // Step 3: Decide interpretation strategy based on table composition
            result = if (tablesWithMedia.isEmpty()) {
                // Only media-free tables exist: interpret immediately
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
        }
        logger.debug("Table identification and media-free interpretation took {} ms", duration)
        return result
    }

    /**
     * Interpret tables and return replacements without applying them.
     */
    private suspend fun interpretAndGetReplacements(
        webpage: IBrowserPage,
        tables: List<TableIdentification>,
        sessionId: SessionId
    ): List<IBrowserPage.CssSelectorReplacementWithText> {
        if (tables.isEmpty()) {
            return emptyList()
        }

        // Batch check which tables still exist (single CDP call)
        val allSelectors = tables.map { it.cssSelector }
        val existenceMap = webpage.elementsExistByCssSelectors(allSelectors)

        // Filter tables that still exist
        val existingTables = tables.filter { table ->
            existenceMap[table.cssSelector] == true
        }

        if (existingTables.isEmpty()) {
            return emptyList()
        }

        val tableInputs = existingTables.map { table ->
            table.cssSelector to TableInterpretationInput(
                tableIdentification = table,
                webpage = webpage
            )
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
        if (semanticElements.popups.isEmpty()) {
            return null
        }

        // Use batch operation to extract text from all popups in a single browser call
        val popupSelectors = semanticElements.popups.map { it.cssSelector }
        val textBySelector = webpage.extractElementsTextContentByCssSelectors(popupSelectors)

        return buildString {
            textBySelector.values.forEach { text ->
                if (text.isNotBlank()) {
                    appendLine(text)
                }
            }
        }.takeIf { it.isNotBlank() }
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