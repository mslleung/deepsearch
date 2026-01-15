package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IVisualIdentificationService
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.services.ContentLlmResults
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Phase 6 of ContentLlmBatchHandler: Process page-level batch results.
 * Handles visual identification results (semantic elements + tables) and stores to GCS.
 */
class PageResultProcessor(
    private val geminiBatchService: IGeminiBatchService,
    private val jsoupDomService: IJsoupDomService,
    private val visualIdentificationService: IVisualIdentificationService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder
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

                // Inject both semantic and table IDs into HTML
                val doc = Jsoup.parse(pending.htmlWithIds)

                // Inject semantic element IDs
                val semanticInjections = buildSemanticInjections(visualResult.semanticElements)
                jsoupDomService.injectIdentifiers(doc, semanticInjections)

                // Inject table IDs
                val tableInjections = visualResult.tables.map { it.cssSelector to it.dataId }
                jsoupDomService.injectIdentifiers(doc, tableInjections)

                // Store results to GCS
                snapshotStorage.storeContentLlmResults(
                    basePath,
                    ContentLlmResults(
                        cleanedHtml = doc.html(),
                        semanticElements = visualResult.semanticElements,
                        tableIdentifications = visualResult.tables,
                        iconInterpretations = null,
                        imageTexts = null
                    )
                )

                logger.debug(
                    "[{}] Processed visual ID for {}: {} semantic, {} tables",
                    jobId, urlState.url,
                    countSemanticElements(visualResult.semanticElements),
                    visualResult.tables.size
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

            // Inject both semantic and table IDs into HTML
            val doc = Jsoup.parse(pageData.html)

            // Inject semantic element IDs
            val semanticInjections = buildSemanticInjections(visualResult.semanticElements)
            jsoupDomService.injectIdentifiers(doc, semanticInjections)

            // Inject table IDs
            val tableInjections = visualResult.tables.map { it.cssSelector to it.dataId }
            jsoupDomService.injectIdentifiers(doc, tableInjections)

            // Store results to GCS
            snapshotStorage.storeContentLlmResults(
                basePath,
                ContentLlmResults(
                    cleanedHtml = doc.html(),
                    semanticElements = visualResult.semanticElements,
                    tableIdentifications = visualResult.tables,
                    iconInterpretations = null,
                    imageTexts = null
                )
            )
        }
    }

    private fun buildSemanticInjections(elements: SemanticElements): List<Pair<String, String>> {
        return buildList {
            elements.header?.let { add(it.cssSelector to it.dataId) }
            elements.footer?.let { add(it.cssSelector to it.dataId) }
            elements.navSidebar?.let { add(it.cssSelector to it.dataId) }
            elements.breadcrumb?.let { add(it.cssSelector to it.dataId) }
            elements.cookieBanner?.let { add(it.cssSelector to it.dataId) }
            addAll(elements.adBanners.map { it.cssSelector to it.dataId })
            addAll(elements.popups.map { it.cssSelector to it.dataId })
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
