package io.deepsearch.application.services

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.IJsoupDomService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
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
    val imageHashes: List<ByteArray> = emptyList(),
    /**
     * Mapping of image numbers to original image hash IDs.
     * Format: {"1": "img-abc123", "2": "img-def456"}
     * Used to resolve ![description](#img-N) references in markdown back to actual image hashes.
     */
    val imageMapping: Map<String, String> = emptyMap()
)

interface IWebpageExtractionService {
    suspend fun extractWebpage(
        webpage: IBrowserPage,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
    ): WebpageExtractionResult
}

class WebpageExtractionService(
    private val visualIdentificationService: IVisualIdentificationService,
    private val tableInterpretationService: ITableInterpretationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val boundingBoxDerivationService: IBoundingBoxDerivationService,
    private val jsoupDomService: IJsoupDomService,
    private val markdownFormattingService: IMarkdownFormattingService
) : IWebpageExtractionService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // ========== Data Classes for Pipeline Stages ==========

    private data class LlmResults(
        val semanticElements: SemanticElements,
        val tableIdentifications: List<TableIdentification>,
        val iconReplacements: List<CssSelectorReplacement>,
        val imageReplacements: List<CssSelectorReplacement>,
        val imageHashes: List<ByteArray>,
        /** Mapping of image numbers to original image hash IDs: {"1": "img-abc123"} */
        val imageMapping: Map<String, String>
    )

    // ========== Pipeline Extension for Flow Composition ==========

    /**
     * Chains a transformation onto a Deferred, creating a new pipelined async.
     * The transformation starts as soon as the upstream value is available.
     */
    private fun <T, R> CoroutineScope.pipeFrom(
        upstream: Deferred<T>,
        transform: suspend (T) -> R
    ): Deferred<R> = async { transform(upstream.await()) }

    /**
     * Converts a webpage into text for downstream LLM processing.
     * 
     * Pipelined flow with early browser release:
     * 
     *   capturePageSnapshot() ──┬──> identifyVisualElements() (semantic + tables in 1 call)
     *   takeFullPageScreenshot()┘
     *   extractIcons() ────────────> interpretIcons()
     *   extractImages() ───────────> interpretImages()
     *   
     * All browser operations run in parallel for maximum speed.
     * The snapshot HTML doesn't contain data-ds-id attributes (injected by
     * icon/image extraction), but we re-inject them into the Jsoup document
     * during DOM processing using the CSS selectors from extraction results.
     *   
     * >>> BROWSER RELEASED when all browser ops complete <<<
     *   
     *   [All LLM results] ─────────> DOM processing (Jsoup)
     */
    override suspend fun extractWebpage(
        webpage: IBrowserPage,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage
    ): WebpageExtractionResult = coroutineScope {
        logger.debug("Starting pipelined extraction...")
        val result: WebpageExtractionResult
        val totalDuration = measureTimeMillis {
            // ===== Browser Captures (all parallel) =====
            // Screenshot is captured once and shared between:
            // - Visual identification (semantic + tables in single vision call)
            // - Image extraction (fallback cropping for CORS-blocked images)
            val snapshotDeferred = async { webpage.capturePageSnapshot() }
            val screenshotDeferred = async { webpage.takeFullPageScreenshot() }
            val iconsDeferred = async { webpage.extractIcons() }
            // Image extraction uses the shared screenshot for fallback (avoids duplicate screenshot capture)
            val imagesDeferred = async { 
                val screenshot = screenshotDeferred.await()
                webpage.extractImagesWithScreenshot(screenshot)
            }

            // ===== LLM Operations (pipelined from captures) =====
            // Combined visual identification: semantic elements + tables in single LLM call
            val visualId = async {
                val snapshot = snapshotDeferred.await()
                val screenshot = screenshotDeferred.await()
                doIdentifyVisualElements(sessionId, snapshot, screenshot)
            }
            val iconRepl = pipeFrom(iconsDeferred) { doInterpretIcons(it, sessionId) }
            val imageRepl = pipeFrom(imagesDeferred) { doInterpretImages(it, sessionId, ocrLanguage) }

            // ===== Wait for Browser Release Point =====
            val snapshot: IBrowserPage.PageSnapshotWithMetadata
            val browserDuration = measureTimeMillis {
                snapshot = snapshotDeferred.await()
                screenshotDeferred.await() // Wait for screenshot before releasing browser
                iconsDeferred.await()
                imagesDeferred.await()
            }
            logger.debug("Browser captures complete in {} ms - releasing browser", browserDuration)
            webpage.close()

            // ===== Await LLM Results =====
            val llmDuration = measureTimeMillis { awaitAll(visualId, iconRepl, imageRepl) }
            val visualResult = visualId.await()
            val imageResult = imageRepl.await()
            val llmResults = LlmResults(
                semanticElements = visualResult.semanticElements,
                tableIdentifications = visualResult.tables,
                iconReplacements = iconRepl.await().replacements,
                imageReplacements = imageResult.replacements,
                imageHashes = imageResult.hashes,
                imageMapping = imageResult.imageMapping
            )
            logger.debug(
                "LLM operations complete in {} ms: {} semantic, {} tables, {} icons, {} images",
                llmDuration,
                countSemanticElements(llmResults.semanticElements),
                llmResults.tableIdentifications.size,
                llmResults.iconReplacements.size,
                llmResults.imageReplacements.size
            )

            // ===== DOM Processing (Jsoup) + table interpretation =====
            val domResult: WebpageExtractionResult
            val jsoupDuration = measureTimeMillis {
                domResult = processDom(snapshot, llmResults, sessionId)
            }
            logger.debug("Table interpretation complete in {} ms", jsoupDuration)

            result = domResult
        }

        logger.info("Webpage extraction completed in {} ms total", totalDuration)
        result
    }

    // ========== Browser Capture Operations ==========

    private suspend fun doIdentifyVisualElements(
        sessionId: SessionId,
        snapshot: IBrowserPage.PageSnapshotWithMetadata,
        screenshot: IBrowserPage.Screenshot
    ): VisualIdentificationResult {
        val result: VisualIdentificationResult
        val duration = measureTimeMillis {
            result = visualIdentificationService.identifyVisualElements(sessionId, snapshot, screenshot)
        }
        logger.debug(
            "Visual identification took {} ms: {} semantic, {} tables ({} hidden containers)",
            duration,
            countSemanticElements(result.semanticElements),
            result.tables.size,
            snapshot.hiddenContainers.size
        )
        return result
    }

    private data class IconInterpretationResult(
        val replacements: List<CssSelectorReplacement>
    )

    private suspend fun doInterpretIcons(
        icons: List<IBrowserPage.Icon>,
        sessionId: SessionId
    ): IconInterpretationResult {
        val result: IconInterpretationResult
        val duration = measureTimeMillis {
            val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons, sessionId)
            val replacements = icons.zip(interpretedTexts).flatMap { (icon, text) ->
                icon.cssSelectors.map { CssSelectorReplacement(it, text) }
            }
            result = IconInterpretationResult(replacements)
        }
        logger.debug("Icon interpretation took {} ms, {} replacements", duration, result.replacements.size)
        return result
    }

    private data class ImageInterpretationResult(
        val replacements: List<CssSelectorReplacement>,
        val hashes: List<ByteArray>,
        /** Mapping of image numbers to original image hash IDs: {"1": "img-abc123"} */
        val imageMapping: Map<String, String>
    )

    private suspend fun doInterpretImages(
        images: List<IBrowserPage.WebImage>,
        sessionId: SessionId,
        ocrLanguage: OcrLanguage
    ): ImageInterpretationResult {
        val result: ImageInterpretationResult
        val duration = measureTimeMillis {
            val extractionResults = webpageImageTextExtractionService.extractTextFromImagesWithHashes(
                images, sessionId, ocrLanguage
            )

            // Track image number assignments for the number-to-hash mapping
            var imageCounter = 0
            val imageMapping = mutableMapOf<String, String>()

            val replacements = images.zip(extractionResults).flatMap { (image, extraction) ->
                // Only assign a number if there's extracted text (image is relevant)
                val wrappedText = if (extraction.extractedText != null) {
                    imageCounter++
                    val imageNumber = imageCounter.toString()
                    val imageId = extraction.toImageId()
                    imageMapping[imageNumber] = imageId
                    wrapImageTextAsMarkdown(extraction.extractedText, imageNumber)
                } else {
                    null
                }
                image.cssSelectors.map { CssSelectorReplacement(it, wrappedText) }
            }
            result = ImageInterpretationResult(
                replacements = replacements,
                hashes = extractionResults.map { it.imageBytesHash },
                imageMapping = imageMapping
            )
        }
        logger.debug(
            "Image interpretation took {} ms, {} replacements, {} mapped images",
            duration, result.replacements.size, result.imageMapping.size
        )
        return result
    }

    // ========== DOM Processing ==========

    private suspend fun processDom(
        snapshot: IBrowserPage.PageSnapshotWithMetadata,
        llmResults: LlmResults,
        sessionId: SessionId
    ): WebpageExtractionResult {
        val imageMapping = llmResults.imageMapping
        val jsoupDoc = Jsoup.parse(snapshot.html)

        // ===== Step 1: Inject all identifiers into Jsoup document =====
        // This restores the original behavior where data-ds-id attributes were
        // injected into the browser DOM for stable subsequent operations.

        // Inject media identifiers (icons + images)
        jsoupDomService.injectMediaIdentifiers(jsoupDoc)

        // Inject semantic element identifiers using CSS selectors from agents
        val semanticInjections = collectSemanticInjections(llmResults.semanticElements)
        jsoupDomService.injectIdentifiers(jsoupDoc, semanticInjections)

        // Inject table identifiers using CSS selectors from agents
        val tableInjections = llmResults.tableIdentifications.map { it.cssSelector to it.dataId }
        jsoupDomService.injectIdentifiers(jsoupDoc, tableInjections)

        // ===== Step 2: Apply media replacements (icons + images) =====
        val mediaReplacements = llmResults.iconReplacements + llmResults.imageReplacements
        jsoupDomService.replaceElementsWithText(jsoupDoc, mediaReplacements)

        // ===== Step 3: Extract popup text before removal =====
        // Use stable data-ds-id selectors instead of position-based CSS selectors
        val popupText = llmResults.semanticElements.popups
            .map { "[data-ds-id=\"${it.dataId}\"]" }
            .let { jsoupDomService.extractElementsText(jsoupDoc, it) }
            .values
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

        // ===== Step 4: Remove semantic elements =====
        // Use stable data-ds-id selectors instead of position-based CSS selectors
        jsoupDomService.removeElements(jsoupDoc, collectSemanticDataIdSelectors(llmResults.semanticElements))

        // ===== Step 5: Interpret and replace tables =====
        // Tables use data-ds-id selectors which remain stable after semantic removal
        val tableReplacements = interpretTablesWithDerivedData(
            llmResults.tableIdentifications,
            snapshot.html,
            snapshot.boundingBoxes,
            jsoupDoc,
            sessionId
        )
        jsoupDomService.replaceElementsWithText(jsoupDoc, tableReplacements)

        // ===== Step 6: Extract final text =====
        val extractedText = jsoupDomService.extractTextContent(jsoupDoc)

        // ===== Step 7: Format markdown using LLM =====
//        val markdown = markdownFormattingService.formatMarkdown(
//            rawText = extractedText,
//            url = snapshot.url,
//            title = snapshot.title,
//            description = snapshot.description,
//            popupText = popupText,
//            sessionId = sessionId
//        )
        val markdown = buildString {
            appendLine("URL: ${snapshot.url}")
            if (snapshot.title.isNotBlank()) {
                appendLine("Title: ${snapshot.title}")
            }
            if (!snapshot.description.isNullOrBlank()) {
                appendLine("Description: ${snapshot.description}")
            }
            appendLine()
            append(extractedText)
        }

        return WebpageExtractionResult(
            markdown = markdown,
            title = snapshot.title,
            description = snapshot.description,
            imageHashes = llmResults.imageHashes,
            imageMapping = imageMapping
        )
    }

    /**
     * Wraps extracted image text as markdown image syntax.
     * Format: ![description](#img-N) where N is the sequential image number.
     * 
     * For multiline descriptions, uses a condensed single-line format.
     */
    private fun wrapImageTextAsMarkdown(text: String, imageNumber: String): String {
        // Condense multiline text to single line for markdown alt text
        val condensedText = text.replace('\n', ' ').replace("\\s+".toRegex(), " ").trim()
        return "![${condensedText}](#img-$imageNumber)"
    }

    /**
     * Interprets tables using pre-computed data derived from the page snapshot.
     * Uses Jsoup document for HTML extraction (after media replacement).
     */
    private suspend fun interpretTablesWithDerivedData(
        tables: List<TableIdentification>,
        originalHtml: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        jsoupDoc: org.jsoup.nodes.Document,
        sessionId: SessionId
    ): List<CssSelectorReplacement> {
        if (tables.isEmpty()) {
            return emptyList()
        }

        // Derive bounding boxes for all tables from page snapshot
        val derivedDataMap = boundingBoxDerivationService.deriveElementsBoundingBoxes(
            cssSelectors = tables.map { it.cssSelector },
            html = originalHtml,
            pageBoundingBoxes = pageBoundingBoxes
        )

        // Build table interpretation inputs using:
        // - HTML from Jsoup doc using data-ds-id selectors (stable after semantic removal)
        // - Bounding boxes derived from page snapshot
        val tableInputs = tables.mapNotNull { table ->
            val derivedData = derivedDataMap[table.cssSelector]

            // Use stable data-ds-id selector (identifiers were injected earlier)
            val dataIdSelector = "[data-ds-id=\"${table.dataId}\"]"
            val currentHtml = jsoupDomService.getElementHtml(jsoupDoc, dataIdSelector)
            if (currentHtml == null) {
                // Table was likely removed with a semantic element - this is expected
                logger.debug("Table element not found (may have been removed with semantic element): {}", table.dataId)
                return@mapNotNull null
            }

            // Use derived bounding boxes, or empty if not available
            val boundingBoxes = derivedData?.boundingBoxes ?: emptyMap()

            TableInterpretationInput(
                tableIdentification = table,
                tableHtml = currentHtml,
                boundingBoxes = boundingBoxes
            )
        }

        if (tableInputs.isEmpty()) {
            return emptyList()
        }

        // Interpret all tables in batch
        val markdowns = tableInterpretationService.interpretTablesBatch(tableInputs, sessionId)

        // Return replacements using stable data-ds-id selectors
        return tableInputs.zip(markdowns).map { (input, markdown) ->
            CssSelectorReplacement("[data-ds-id=\"${input.tableIdentification.dataId}\"]", markdown)
        }
    }

    /**
     * Collects (cssSelector, dataId) pairs for injecting identifiers into Jsoup document.
     */
    private fun collectSemanticInjections(semanticElements: SemanticElements): List<Pair<String, String>> {
        return buildList {
            semanticElements.header?.let { add(it.cssSelector to it.dataId) }
            semanticElements.footer?.let { add(it.cssSelector to it.dataId) }
            semanticElements.navSidebar?.let { add(it.cssSelector to it.dataId) }
            semanticElements.breadcrumb?.let { add(it.cssSelector to it.dataId) }
            semanticElements.cookieBanner?.let { add(it.cssSelector to it.dataId) }
            addAll(semanticElements.adBanners.map { it.cssSelector to it.dataId })
            addAll(semanticElements.popups.map { it.cssSelector to it.dataId })
        }
    }

    /**
     * Collects stable data-ds-id selectors for removing semantic elements.
     * These selectors remain valid regardless of DOM structure changes.
     */
    private fun collectSemanticDataIdSelectors(semanticElements: SemanticElements): List<String> {
        return buildList {
            semanticElements.header?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.footer?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.navSidebar?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.breadcrumb?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            semanticElements.cookieBanner?.let { add("[data-ds-id=\"${it.dataId}\"]") }
            addAll(semanticElements.adBanners.map { "[data-ds-id=\"${it.dataId}\"]" })
            addAll(semanticElements.popups.map { "[data-ds-id=\"${it.dataId}\"]" })
        }
    }

    private fun countSemanticElements(elements: SemanticElements): Int {
        var count = 0
        if (elements.header != null) count++
        if (elements.footer != null) count++
        if (elements.navSidebar != null) count++
        if (elements.breadcrumb != null) count++
        if (elements.cookieBanner != null) count++
        count += elements.adBanners.size
        count += elements.popups.size
        return count
    }
}
