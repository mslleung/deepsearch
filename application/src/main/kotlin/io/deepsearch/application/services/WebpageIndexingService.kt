package io.deepsearch.application.services

import io.deepsearch.domain.agents.ISemanticTableClassificationAgent
import io.deepsearch.domain.agents.SemanticTableClassificationInput
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.ISemanticListConverter
import io.deepsearch.domain.services.ISemanticTableConverter
import io.deepsearch.domain.services.MediaPlaceholderMapping
import io.deepsearch.domain.services.PlaceholderPrefix
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Result of webpage indexing. Contains comprehensive markdown from DOM extraction,
 * including non-visible content (accordion bodies, tab panels) for thorough vector indexing.
 */
data class WebpageIndexResult(
    val markdown: String,
    val title: String?,
    val description: String?
)

interface IWebpageIndexingService {
    /**
     * Produce index-quality markdown from a loaded browser page.
     *
     * Pipeline: inject IDs -> capture (snapshot + screenshot + icons) -> visual identification
     * -> icon interpretation -> DOM processing (semantic removal, table interpretation, list
     * conversion, HTML-to-markdown).
     *
     * Captures comprehensive content (including hidden/collapsed elements) for vector store
     * routing. Query-time accuracy is ensured by agentic VLM search, not this pipeline.
     */
    suspend fun indexWebpage(
        page: IBrowserPage,
        sessionId: SessionId
    ): WebpageIndexResult

    /**
     * Index from pre-captured browser data (no browser access needed).
     * Used during query-time background indexing: the orchestrator performs a quick capture
     * (~0.5s), then this method processes the captured data server-side while the browser
     * is freed for agentic search.
     */
    suspend fun indexFromCapturedData(
        capturedData: QuickCaptureData,
        sessionId: SessionId
    ): WebpageIndexResult
}

/**
 * Data captured during a quick browser pass, sufficient for server-side indexing.
 */
data class QuickCaptureData(
    val snapshot: IBrowserPage.PageSnapshotWithMetadata,
    val screenshot: IBrowserPage.Screenshot,
    val icons: List<IBrowserPage.Icon>
)

/**
 * Captures essential browser data quickly (~0.5s) without closing the page.
 * The page remains open for subsequent agentic search.
 */
suspend fun quickCapture(page: IBrowserPage): QuickCaptureData {
    page.injectStableIds()
    val snapshot = page.capturePageSnapshot()
    val screenshot = page.takeFullPageScreenshot()
    val icons = page.extractIcons()
    return QuickCaptureData(snapshot, screenshot, icons)
}

class WebpageIndexingService(
    private val visualIdentificationService: IVisualIdentificationService,
    private val tableInterpretationService: ITableInterpretationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val boundingBoxDerivationService: IBoundingBoxDerivationService,
    private val jsoupDomService: IJsoupDomService,
    private val htmlToMarkdownService: IHtmlToMarkdownService,
    private val semanticTableConverter: ISemanticTableConverter,
    private val semanticListConverter: ISemanticListConverter,
    private val semanticTableClassificationAgent: ISemanticTableClassificationAgent,
    private val tokenUsageService: ILlmTokenUsageService
) : IWebpageIndexingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun indexWebpage(
        page: IBrowserPage,
        sessionId: SessionId
    ): WebpageIndexResult {
        val result: WebpageIndexResult
        val totalDuration = measureTimeMillis {
            val capturedData: QuickCaptureData
            val captureDuration = measureTimeMillis {
                capturedData = quickCapture(page)
            }
            logger.debug("Browser capture complete in {} ms", captureDuration)

            result = indexFromCapturedData(capturedData, sessionId)
        }
        logger.info("Webpage indexing (with capture) completed in {} ms", totalDuration)
        return result
    }

    override suspend fun indexFromCapturedData(
        capturedData: QuickCaptureData,
        sessionId: SessionId
    ): WebpageIndexResult {
        val result: WebpageIndexResult
        val totalDuration = measureTimeMillis {
            result = runPipeline(capturedData, sessionId)
        }
        logger.info(
            "Webpage indexing completed in {} ms: {} chars markdown",
            totalDuration, result.markdown.length
        )
        return result
    }

    // ========== Pipeline ==========

    private suspend fun runPipeline(
        data: QuickCaptureData,
        sessionId: SessionId
    ): WebpageIndexResult = coroutineScope {
        val snapshot = data.snapshot
        val screenshot = data.screenshot

        // Phase 1: LLM operations (parallel)
        val visualIdDeferred = async {
            val r: VisualIdentificationResult
            val d = measureTimeMillis {
                r = visualIdentificationService.identifyVisualElements(sessionId, snapshot, screenshot)
            }
            logger.debug("Visual identification: {} ms", d)
            r
        }

        val iconReplDeferred = async {
            val replacements: List<CssSelectorReplacement>
            val d = measureTimeMillis {
                replacements = interpretIcons(data.icons, sessionId)
            }
            logger.debug("Icon interpretation: {} ms, {} replacements", d, replacements.size)
            replacements
        }

        val visualResult = visualIdDeferred.await()
        val iconReplacements = iconReplDeferred.await()

        // Phase 2: DOM processing (server-side, no browser)
        val rawMarkdown: String
        val domDuration = measureTimeMillis {
            rawMarkdown = processDom(snapshot, visualResult, iconReplacements, sessionId)
        }
        logger.debug("DOM processing: {} ms, {} chars raw markdown", domDuration, rawMarkdown.length)

        val markdown = buildString {
            appendLine("URL: ${snapshot.url}")
            if (snapshot.title.isNotBlank()) {
                appendLine("Title: ${snapshot.title}")
            }
            if (!snapshot.description.isNullOrBlank()) {
                appendLine("Description: ${snapshot.description}")
            }
            appendLine()
            append(rawMarkdown)
        }

        WebpageIndexResult(
            markdown = markdown,
            title = snapshot.title.takeIf { it.isNotBlank() },
            description = snapshot.description?.takeIf { it.isNotBlank() }
        )
    }

    // ========== Icon Interpretation ==========

    private suspend fun interpretIcons(
        icons: List<IBrowserPage.Icon>,
        sessionId: SessionId
    ): List<CssSelectorReplacement> {
        if (icons.isEmpty()) return emptyList()

        val interpretedTexts = webpageIconInterpretationService.interpretIcons(icons, sessionId)
        return icons.zip(interpretedTexts).flatMap { (icon, text) ->
            icon.cssSelectors.map { selector ->
                CssSelectorReplacement(selector, text?.let { "{$it icon}" })
            }
        }
    }

    // ========== DOM Processing ==========

    private suspend fun processDom(
        snapshot: IBrowserPage.PageSnapshotWithMetadata,
        visualResult: VisualIdentificationResult,
        iconReplacements: List<CssSelectorReplacement>,
        sessionId: SessionId
    ): String = coroutineScope {
        val jsoupDoc = Jsoup.parse(snapshot.html)

        // Step 1: Apply icon replacements with placeholders
        val placeholderMap = jsoupDomService.replaceElementsWithPlaceholders(jsoupDoc, iconReplacements)
            .toMutableMap()

        // Step 2: Remove semantic elements (header, footer, nav, cookie banner, etc.)
        jsoupDomService.removeElements(jsoupDoc, collectSemanticDataIdSelectors(visualResult.semanticElements))

        // Step 3: Extract semantic tables and lists (static analysis, no LLM)
        val semanticTables = extractSemanticTables(snapshot.html)
        val semanticLists = extractSemanticLists(snapshot.html)
        logger.debug(
            "Static analysis: {} semantic <table>, {} semantic lists, {} visual tables",
            semanticTables.size, semanticLists.size, visualResult.tables.size
        )

        // Step 4: Three parallel interpretation paths
        val visualTablesDeferred = async {
            interpretVisualTables(
                visualResult.tables, snapshot.html, snapshot.boundingBoxes,
                jsoupDoc, placeholderMap, sessionId
            )
        }

        val semanticTablesDeferred = async {
            interpretSemanticTables(semanticTables, placeholderMap)
        }

        val semanticListsDeferred = async {
            interpretSemanticLists(semanticLists, placeholderMap)
        }

        val visualTableReplacements = visualTablesDeferred.await()
        val semanticTableReplacements = semanticTablesDeferred.await()
        val semanticListReplacements = semanticListsDeferred.await()

        // Step 5: Apply table replacements with placeholders
        val allTableReplacements = visualTableReplacements + semanticTableReplacements
        val tablePlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
            jsoupDoc, allTableReplacements, PlaceholderPrefix.TABLE
        )
        placeholderMap.putAll(tablePlaceholders)

        val listPlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
            jsoupDoc, semanticListReplacements, PlaceholderPrefix.LIST
        )
        placeholderMap.putAll(listPlaceholders)

        // Step 6: Cleanup and convert HTML to markdown
        jsoupDomService.cleanupForMarkdownConversion(jsoupDoc)
        val rawMarkdown = htmlToMarkdownService.convert(jsoupDoc.html())

        // Step 7: Replace placeholders with actual text
        var finalMarkdown = rawMarkdown
        placeholderMap.values.forEach { mapping ->
            finalMarkdown = finalMarkdown.replace(mapping.placeholder, mapping.text)
        }

        finalMarkdown
    }

    // ========== Table Interpretation ==========

    private suspend fun interpretVisualTables(
        tables: List<TableIdentification>,
        originalHtml: String,
        pageBoundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        jsoupDoc: org.jsoup.nodes.Document,
        placeholderMap: Map<String, MediaPlaceholderMapping>,
        sessionId: SessionId
    ): List<CssSelectorReplacement> {
        if (tables.isEmpty()) return emptyList()

        val derivedDataMap = boundingBoxDerivationService.deriveElementsBoundingBoxes(
            cssSelectors = tables.map { it.cssSelector },
            html = originalHtml,
            pageBoundingBoxes = pageBoundingBoxes
        )

        val tableInputs = tables.mapNotNull { table ->
            val derivedData = derivedDataMap[table.cssSelector]
            val dataIdSelector = "[data-ds-id=\"${table.dataId}\"]"
            val tableHtmlWithPlaceholders = jsoupDomService.getElementHtml(jsoupDoc, dataIdSelector)
            if (tableHtmlWithPlaceholders == null) {
                logger.debug("Table element not found (removed with semantic element): {}", table.dataId)
                return@mapNotNull null
            }

            val tableHtmlForLlm = placeholderMap.values.fold(tableHtmlWithPlaceholders) { html, mapping ->
                html.replace(mapping.placeholder, mapping.text)
            }

            TableInterpretationInput(
                tableIdentification = table,
                tableHtml = tableHtmlForLlm,
                boundingBoxes = derivedData?.boundingBoxes ?: emptyMap()
            )
        }

        if (tableInputs.isEmpty()) return emptyList()

        val results = tableInterpretationService.interpretTablesBatch(tableInputs, sessionId)

        return tableInputs.zip(results).map { (input, result) ->
            val replacementText = if (result.classification.shouldRemoveFromDom()) null else result.markdown
            CssSelectorReplacement("[data-ds-id=\"${input.tableIdentification.dataId}\"]", replacementText)
        }
    }

    private suspend fun interpretSemanticTables(
        semanticTables: List<SemanticTableData>,
        placeholderMap: Map<String, MediaPlaceholderMapping>
    ): List<CssSelectorReplacement> {
        if (semanticTables.isEmpty()) return emptyList()

        val markdowns = semanticTables.map { table ->
            val resolvedHtml = placeholderMap.values.fold(table.tableHtml) { html, mapping ->
                html.replace(mapping.placeholder, mapping.text)
            }
            semanticTableConverter.convertToMarkdown(resolvedHtml)
        }

        val classificationInputs = markdowns.map { SemanticTableClassificationInput(markdownTable = it) }
        val classificationResult = semanticTableClassificationAgent.classifyTables(classificationInputs)
        val classifications = classificationResult.classifications

        return semanticTables.zip(markdowns).zip(classifications).map { (pair, classification) ->
            val (table, markdown) = pair
            val replacementText = if (classification.shouldRemoveFromDom()) null else markdown
            CssSelectorReplacement(table.cssSelector, replacementText)
        }
    }

    private fun interpretSemanticLists(
        semanticLists: List<SemanticListData>,
        placeholderMap: Map<String, MediaPlaceholderMapping>
    ): List<CssSelectorReplacement> {
        if (semanticLists.isEmpty()) return emptyList()

        return semanticLists.map { list ->
            val resolvedHtml = placeholderMap.values.fold(list.listHtml) { html, mapping ->
                html.replace(mapping.placeholder, mapping.text)
            }
            val markdown = semanticListConverter.convertToMarkdown(resolvedHtml)
            CssSelectorReplacement(list.cssSelector, markdown.ifBlank { null })
        }
    }

    // ========== Static Analysis Helpers ==========

    private fun extractSemanticTables(html: String): List<SemanticTableData> {
        val doc = Jsoup.parse(html)
        return doc.select("table[data-ds-id]").map { element ->
            val dataId = element.attr("data-ds-id")
            SemanticTableData(
                dataId = dataId,
                cssSelector = "[data-ds-id=\"$dataId\"]",
                tableHtml = element.outerHtml()
            )
        }
    }

    private fun extractSemanticLists(html: String): List<SemanticListData> {
        val doc = Jsoup.parse(html)
        return doc.select("ul[data-ds-id], ol[data-ds-id]")
            .filter { element -> element.parents().none { it.tagName() in listOf("ul", "ol") } }
            .map { element ->
                val dataId = element.attr("data-ds-id")
                SemanticListData(
                    dataId = dataId,
                    cssSelector = "[data-ds-id=\"$dataId\"]",
                    listHtml = element.outerHtml(),
                    isOrdered = element.tagName() == "ol"
                )
            }
    }

    private fun collectSemanticDataIdSelectors(
        semanticElements: io.deepsearch.domain.models.valueobjects.SemanticElements
    ): List<String> {
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

    /**
     * Reuses the data classes from [WebpageExtractionService] since the static analysis
     * logic for semantic tables and lists is identical.
     */
    private data class SemanticTableData(
        val dataId: String,
        val cssSelector: String,
        val tableHtml: String
    )

    private data class SemanticListData(
        val dataId: String,
        val cssSelector: String,
        val listHtml: String,
        val isOrdered: Boolean
    )
}
