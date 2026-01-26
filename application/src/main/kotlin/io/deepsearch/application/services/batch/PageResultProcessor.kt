package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IVisualIdentificationService
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.services.ContentLlmResults
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.ICssSelectorConstructionService
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.ITableGridDetectorService
import io.deepsearch.domain.services.SemanticTableData
import io.deepsearch.domain.services.TableDetectionBoundingBox
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Phase 6 of ContentLlmBatchHandler: Process page-level batch results.
 * Handles visual identification results (semantic elements + tables) and stores to GCS.
 * 
 * Also detects hidden tables using TableGridDetectorService (spatial analysis) and merges
 * them with visible tables from visual identification.
 */
class PageResultProcessor(
    private val geminiBatchService: IGeminiBatchService,
    private val jsoupDomService: IJsoupDomService,
    private val visualIdentificationService: IVisualIdentificationService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder,
    private val tableGridDetectorService: ITableGridDetectorService,
    private val cssSelectorConstructionService: ICssSelectorConstructionService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Process page-level visual identification results.
     *
     * @param batches Submitted batch IDs
     * @param preparations Batch preparations
     * @param urlStates URL states being processed
     * @param collected Content collection result
     * @param jobId Job ID for logging
     */
    suspend fun process(
        batches: SubmittedBatches,
        preparations: ContentBatchPreparer.BatchPreparations,
        urlStates: List<BatchUrlState>,
        collected: ContentCollectionResult,
        jobId: Long
    ) {
        if (batches.visualId == null) return

        val visualResults = geminiBatchService.fetchBatchResults(batches.visualId)

        // Record token usage
        val visualModelId = preparations.visualPrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
        batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "VisualIdentificationBatch", visualModelId, visualResults)

        visualResults.forEachIndexed { index, result ->
            val pending = preparations.visualPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
            val urlState = urlStates.find { it.id == pending.urlStateId.value } ?: return@forEachIndexed
            val basePath = urlState.snapshotBasePath ?: return@forEachIndexed
            val pageData = collected.urlPages[pending.urlStateId] ?: return@forEachIndexed

            try {
                if (!result.success || result.generatedText == null) {
                    logger.warn("[{}] Visual ID batch failed for {}: {}", jobId, urlState.url, result.errorMessage)
                    return@forEachIndexed
                }

                // Get bounding boxes from collected data (already loaded from GCS)
                val boundingBoxes = pageData.boundingBoxes
                val pageWidth = boundingBoxes.values.maxOfOrNull { it.right } ?: 1920.0
                val pageHeight = boundingBoxes.values.maxOfOrNull { it.bottom } ?: 1080.0

                val visualResult = visualIdentificationService.parseBatchResponse(
                    result.generatedText!!,
                    pending.htmlWithIds,
                    boundingBoxes,
                    pageWidth,
                    pageHeight
                )

                // HTML already has data-ds-id attributes from browser's injectStableIds()
                val doc = Jsoup.parse(pending.htmlWithIds)

                // Detect hidden tables using spatial analysis
                val hiddenBboxData = snapshotStorage.readHiddenContainerBoundingBoxes(basePath)
                val hiddenTableCandidates = if (hiddenBboxData != null) {
                    identifyHiddenTableCandidates(hiddenBboxData, pending.htmlWithIds)
                } else {
                    emptyList()
                }

                // Extract semantic <table> elements (static analysis, no LLM)
                val semanticTables = extractSemanticTables(pending.htmlWithIds)
                
                // Collect all table data-ds-ids for overlap detection
                val visualTableDataIds = visualResult.tables.map { it.dataId }.toSet()
                val semanticTableDataIds = semanticTables.map { it.dataId }.toSet()
                val allTableDataIds = visualTableDataIds + semanticTableDataIds
                
                // Filter hidden tables that overlap with visible/semantic tables
                val filteredHiddenCandidates = filterHiddenCandidatesOverlapping(
                    hiddenTableCandidates,
                    allTableDataIds,
                    pending.htmlWithIds
                )

                // Merge visible tables with filtered hidden table candidates
                val allVisualTables = mergeVisibleAndHiddenTables(
                    visualResult.tables,
                    filteredHiddenCandidates,
                    pending.htmlWithIds
                )

                val hiddenTableCount = filteredHiddenCandidates.size
                val filteredCount = hiddenTableCandidates.size - filteredHiddenCandidates.size

                // Store results to GCS
                snapshotStorage.storeContentLlmResults(
                    basePath,
                    ContentLlmResults(
                        cleanedHtml = doc.html(),
                        semanticElements = visualResult.semanticElements,
                        tableIdentifications = allVisualTables,
                        semanticTableData = semanticTables,
                        iconInterpretations = null,
                        imageTexts = null
                    )
                )

                if (filteredCount > 0) {
                    logger.debug(
                        "[{}] Overlap detection: filtered {} hidden tables inside visible/semantic tables",
                        jobId, filteredCount
                    )
                }

                logger.debug(
                    "[{}] Processed visual ID for {}: {} semantic elements, {} visual tables, {} semantic tables, {} hidden tables",
                    jobId, urlState.url,
                    countSemanticElements(visualResult.semanticElements),
                    visualResult.tables.size,
                    semanticTables.size,
                    hiddenTableCount
                )
            } catch (e: Exception) {
                logger.warn("[{}] Failed to process visual ID result for {}: {}", jobId, urlState.url, e.message)
            }
        }
    }

    /**
     * Apply cached visual identification results.
     * Called before submitting batch requests for URLs that have cached results.
     */
    suspend fun applyCachedResults(
        preparations: ContentBatchPreparer.BatchPreparations,
        urlStates: List<BatchUrlState>,
        collected: ContentCollectionResult
    ) {
        for ((urlStateId, visualResult) in preparations.visualPrep.cachedResults) {
            val urlState = urlStates.find { it.id == urlStateId.value } ?: continue
            val basePath = urlState.snapshotBasePath ?: continue
            val pageData = collected.urlPages[urlStateId] ?: continue

            // HTML already has data-ds-id attributes from browser's injectStableIds()
            val doc = Jsoup.parse(pageData.html)

            // Detect hidden tables using spatial analysis
            val hiddenBboxData = snapshotStorage.readHiddenContainerBoundingBoxes(basePath)
            val hiddenTableCandidates = if (hiddenBboxData != null) {
                identifyHiddenTableCandidates(hiddenBboxData, pageData.html)
            } else {
                emptyList()
            }

            // Extract semantic <table> elements (static analysis, no LLM)
            val semanticTables = extractSemanticTables(pageData.html)
            
            // Collect all table data-ds-ids for overlap detection
            val visualTableDataIds = visualResult.tables.map { it.dataId }.toSet()
            val semanticTableDataIds = semanticTables.map { it.dataId }.toSet()
            val allTableDataIds = visualTableDataIds + semanticTableDataIds
            
            // Filter hidden tables that overlap with visible/semantic tables
            val filteredHiddenCandidates = filterHiddenCandidatesOverlapping(
                hiddenTableCandidates,
                allTableDataIds,
                pageData.html
            )

            // Merge visible tables with filtered hidden table candidates
            val allVisualTables = mergeVisibleAndHiddenTables(
                visualResult.tables,
                filteredHiddenCandidates,
                pageData.html
            )

            // Store results to GCS
            snapshotStorage.storeContentLlmResults(
                basePath,
                ContentLlmResults(
                    cleanedHtml = doc.html(),
                    semanticElements = visualResult.semanticElements,
                    tableIdentifications = allVisualTables,
                    semanticTableData = semanticTables,
                    iconInterpretations = null,
                    imageTexts = null
                )
            )
        }
    }

    // ==================== Hidden Table Detection ====================

    /** Hidden table candidate detected from spatial analysis */
    private data class HiddenTableCandidate(
        val containerId: String,
        val confidence: Double,
        val rowCount: Int,
        val colCount: Int
    )

    /**
     * Identify table candidates from hidden container bounding boxes using TableGridDetectorService.
     */
    private fun identifyHiddenTableCandidates(
        hiddenBboxData: IBrowserPage.HiddenContainerBoundingBoxes,
        html: String
    ): List<HiddenTableCandidate> {
        val candidates = mutableListOf<HiddenTableCandidate>()

        for (container in hiddenBboxData.hiddenContainers) {
            // Need at least 4 elements for a meaningful table
            if (container.elements.size < 4) continue

            // Convert to detection bounding boxes
            val boxes = container.elements.mapValues { (_, box) ->
                TableDetectionBoundingBox(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom
                )
            }

            val gridResult = tableGridDetectorService.detectTable(boxes)

            if (gridResult.isTable && gridResult.confidence >= 0.5) {
                logger.debug(
                    "Hidden container {} detected as table: {}x{} grid (confidence: {})",
                    container.containerLocator, gridResult.rowCount, gridResult.colCount,
                    "%.2f".format(gridResult.confidence)
                )
                candidates.add(
                    HiddenTableCandidate(
                        containerId = container.containerLocator,
                        confidence = gridResult.confidence,
                        rowCount = gridResult.rowCount,
                        colCount = gridResult.colCount
                    )
                )
            }
        }

        return candidates
    }

    /**
     * Merge visible tables from visual identification with hidden table candidates.
     * Deduplicates based on data-ds-id to avoid processing the same table twice.
     */
    private fun mergeVisibleAndHiddenTables(
        visibleTables: List<TableIdentification>,
        hiddenCandidates: List<HiddenTableCandidate>,
        html: String
    ): List<TableIdentification> {
        if (hiddenCandidates.isEmpty()) return visibleTables

        val existingIds = visibleTables.map { it.dataId }.toSet()
        val newTables = mutableListOf<TableIdentification>()

        for (candidate in hiddenCandidates) {
            // Skip if already identified as visible table
            if (candidate.containerId in existingIds) continue

            // Build CSS selector for this hidden container
            val cssSelector = cssSelectorConstructionService.constructCssSelectorFromIdentifier(
                candidate.containerId,
                html
            ) ?: "[data-ds-id=\"${candidate.containerId}\"]"

            newTables.add(
                TableIdentification(
                    dataId = candidate.containerId,
                    cssSelector = cssSelector,
                    auxiliaryInfo = "Hidden table (${candidate.rowCount}×${candidate.colCount} grid, confidence: ${
                        "%.2f".format(
                            candidate.confidence
                        )
                    })"
                )
            )
        }

        logger.debug("Merged {} visible tables with {} hidden table candidates", visibleTables.size, newTables.size)
        return visibleTables + newTables
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
    
    // ==================== Semantic Table Extraction ====================
    
    /**
     * Extract semantic HTML tables from HTML using static analysis.
     * This is a pure Jsoup operation - no LLM needed for identification.
     */
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
    
    // ==================== Overlap Detection ====================
    
    /**
     * Filter hidden table candidates that are inside visible or semantic tables.
     * This prevents duplicate interpretation when a hidden container is nested inside a visible table.
     */
    private fun filterHiddenCandidatesOverlapping(
        hiddenCandidates: List<HiddenTableCandidate>,
        tableDataIds: Set<String>,
        snapshotHtml: String
    ): List<HiddenTableCandidate> {
        if (tableDataIds.isEmpty()) {
            return hiddenCandidates
        }
        
        val doc = Jsoup.parse(snapshotHtml)
        val tableElements = tableDataIds.mapNotNull { dataId ->
            doc.selectFirst("[data-ds-id=\"$dataId\"]")
        }
        
        return hiddenCandidates.filter { hidden ->
            // Try to find the hidden container in the main snapshot
            val hiddenElement = doc.selectFirst("[data-ds-id=\"${hidden.containerId}\"]")
            if (hiddenElement == null) {
                // Can't find it in main snapshot, keep it
                return@filter true
            }
            
            // Check if this hidden element is inside any visible/semantic table
            val isInsideTable = tableElements.any { tableElement ->
                tableElement.getAllElements().contains(hiddenElement) ||
                    hiddenElement.parents().any { it == tableElement }
            }
            
            if (isInsideTable) {
                logger.debug(
                    "Filtering hidden table {} - nested inside visible/semantic table",
                    hidden.containerId
                )
            }
            
            !isInsideTable
        }
    }
}
