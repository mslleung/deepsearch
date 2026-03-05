package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IHybridSearchIndexingService
import io.deepsearch.application.services.IKnowledgeGraphIndexingService
import io.deepsearch.application.services.IWebpageCacheService
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPipelineMode
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchContentRequest
import io.deepsearch.domain.services.CachingData
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IHtmlToMarkdownService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.MediaFileData
import io.deepsearch.domain.services.MediaPlaceholderMapping
import io.deepsearch.domain.services.PlaceholderPrefix
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stage 4: Parallel embedding and knowledge graph extraction handler.
 *
 * This stage runs two batch jobs in parallel for HTML URLs only:
 * 1. Page embedding batch - generates embeddings for page markdown
 * 2. KG extraction batch - extracts entities and relationships from markdown
 *
 * After both batches complete:
 * - Page embeddings are stored in the webpage cache
 * - KG extraction results are stored in URL state for Stage 5 entity embedding
 * - Pages are cached to the webpage cache service
 *
 * Note: FILE URLs are processed independently by FileUploadBackgroundWorker.
 */
class ParallelEmbeddingAndKgHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val jsoupDomService: IJsoupDomService,
    private val htmlToMarkdownService: IHtmlToMarkdownService,
    private val webpageCacheService: IWebpageCacheService,
    private val hybridSearchIndexingService: IHybridSearchIndexingService,
    private val knowledgeGraphIndexingService: IKnowledgeGraphIndexingService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder,
    private val pollingService: BatchPollingService
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 4: Parallel embedding and KG extraction (HTML only)", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 4: Generating embeddings & extracting knowledge graph...")

        // Get HTML URLs only (FINAL_LLM_DONE)
        val htmlUrlsNeedingCaching = batchUrlStateRepository.findNeedingCaching(jobId)

        logger.info("[{}] {} HTML URLs need processing", jobId, htmlUrlsNeedingCaching.size)

        if (htmlUrlsNeedingCaching.isEmpty()) {
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 4 complete: No HTML URLs to process")
            return
        }

        val sessionId = PeriodicIndexSessionId(jobId)

        // Step 1: Prepare markdown for each HTML URL (reading from GCS)
        val urlDataMap = prepareUrlData(htmlUrlsNeedingCaching, jobId, job.pipelineMode)

        // Step 2: Prepare batch requests for both embeddings and KG extraction
        val (embeddingRequests, embeddingRequestMap) = prepareEmbeddingRequests(urlDataMap, jobId)
        val (kgRequests, kgRequestMap) = prepareKgRequests(urlDataMap, jobId)

        logger.info(
            "[{}] Prepared {} embedding requests and {} KG requests",
            jobId, embeddingRequests.size, kgRequests.size
        )

        // Step 3: Submit both batches and poll in parallel
        val (embeddingResults, kgResults) = submitAndPollBatches(
            job, eventFlow, embeddingRequests, kgRequests, embeddingRequestMap, kgRequestMap, jobId
        )

        // Step 4: Store embeddings using the indexing service
        if (embeddingResults.isNotEmpty()) {
            try {
                hybridSearchIndexingService.processBatchResults(embeddingResults)
                logger.info("[{}] Stored {} embeddings via batch processing", jobId, embeddingResults.size)
            } catch (e: Exception) {
                logger.warn("[{}] Failed to store batch embeddings: {}", jobId, e.message)
            }
        }

        // Step 5: Cache all HTML pages and store KG results for Stage 5
        cacheUrlsAndStoreKgResults(urlDataMap, kgResults, sessionId, job)

        batchJobRepository.update(job)

        val entityCount = kgResults.values.sumOf { it.entities.size }
        val relationshipCount = kgResults.values.sumOf { it.relationships.size }

        job.advanceToNextStage()
        batchJobRepository.update(job)
        eventEmitter.emit(
            job, eventFlow,
            "Stage 4 complete: ${job.urlsCached} pages cached, ${embeddingResults.size} embeddings, " +
                "$entityCount entities, $relationshipCount relationships"
        )
    }

    private data class UrlCacheData(
        val urlState: BatchUrlState,
        val basePath: String,
        val markdown: String,
        val imageMapping: Map<String, String>,
        val cachingData: CachingData?,
        val icons: List<MediaFileData>,
        val images: List<MediaFileData>
    )

    /** Result of building final markdown with image mapping */
    private data class MarkdownBuildResult(
        val markdown: String,
        val imageMapping: Map<String, String>
    )

    private data class KgBatchRequest(
        val urlStateId: Long,
        val url: String,
        val request: BatchContentRequest
    )

    private suspend fun prepareUrlData(
        htmlUrls: List<BatchUrlState>,
        jobId: Long,
        pipelineMode: BatchPipelineMode
    ): Map<Long, UrlCacheData> {
        val urlDataMap = mutableMapOf<Long, UrlCacheData>()

        htmlUrls.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach

                if (pipelineMode == BatchPipelineMode.LIGHTWEIGHT) {
                    val markdown = snapshotStorage.readLightweightMarkdown(basePath)
                    if (markdown == null) {
                        logger.warn("[{}] No lightweight markdown for {}", jobId, urlState.url)
                        return@forEach
                    }
                    urlDataMap[urlState.id!!] = UrlCacheData(
                        urlState = urlState,
                        basePath = basePath,
                        markdown = markdown,
                        imageMapping = emptyMap(),
                        cachingData = null,
                        icons = emptyList(),
                        images = emptyList()
                    )
                } else {
                    val cachingData = snapshotStorage.readForCaching(basePath)
                    val icons = snapshotStorage.readIcons(basePath)
                    val images = snapshotStorage.readImages(basePath)

                    val markdownResult = buildFinalMarkdown(urlState, cachingData, icons, images)
                    urlDataMap[urlState.id!!] = UrlCacheData(
                        urlState = urlState,
                        basePath = basePath,
                        markdown = markdownResult.markdown,
                        imageMapping = markdownResult.imageMapping,
                        cachingData = cachingData,
                        icons = icons,
                        images = images
                    )
                }
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare markdown for HTML {}: {}", jobId, urlState.url, e.message)
            }
        }

        return urlDataMap
    }

    private fun prepareEmbeddingRequests(
        urlDataMap: Map<Long, UrlCacheData>,
        jobId: Long
    ): Pair<List<io.deepsearch.domain.services.BatchEmbeddingRequest>, Map<String, String>> {
        val embeddingRequestMap = mutableMapOf<String, String>() // requestId -> URL
        val embeddingRequests = urlDataMap.mapNotNull { (urlStateId, data) ->
            val requestId = "${jobId}-${urlStateId}-embed"
            embeddingRequestMap[requestId] = data.urlState.url
            hybridSearchIndexingService.prepareBatchRequest(requestId, data.markdown)
        }
        return Pair(embeddingRequests, embeddingRequestMap)
    }

    private fun prepareKgRequests(
        urlDataMap: Map<Long, UrlCacheData>,
        jobId: Long
    ): Pair<List<KgBatchRequest>, Map<String, Long>> {
        val kgRequestMap = mutableMapOf<String, Long>() // requestId -> urlStateId
        val kgRequests = mutableListOf<KgBatchRequest>()

        urlDataMap.forEach { (urlStateId, data) ->
            try {
                val requestId = "${jobId}-${urlStateId}-kg"
                kgRequestMap[requestId] = urlStateId
                val request = knowledgeGraphIndexingService.prepareBatchRequest(
                    requestId = requestId,
                    markdown = data.markdown,
                    sourceUrl = data.urlState.url
                )
                kgRequests.add(
                    KgBatchRequest(
                        urlStateId = urlStateId,
                        url = data.urlState.url,
                        request = request
                    )
                )
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare KG request for {}: {}", jobId, data.urlState.url, e.message)
            }
        }

        return Pair(kgRequests, kgRequestMap)
    }

    private suspend fun submitAndPollBatches(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        embeddingRequests: List<io.deepsearch.domain.services.BatchEmbeddingRequest>,
        kgRequests: List<KgBatchRequest>,
        embeddingRequestMap: Map<String, String>,
        kgRequestMap: Map<String, Long>,
        jobId: Long
    ): Pair<Map<String, List<Float>>, Map<Long, KgExtractionResult>> {
        val embeddingResults = mutableMapOf<String, List<Float>>() // URL -> embedding
        val kgResults = mutableMapOf<Long, KgExtractionResult>() // urlStateId -> extraction result

        try {
            eventEmitter.emit(
                job, eventFlow,
                "Submitting parallel batches: ${embeddingRequests.size} embeddings, ${kgRequests.size} KG extractions..."
            )

            // Submit embedding batch if we have requests
            val embeddingBatchId = if (embeddingRequests.isNotEmpty()) {
                geminiBatchService.createEmbeddingBatch(embeddingRequests).also { batchId ->
                    job.addBatchJob(batchId)
                    logger.info("[{}] Submitted embedding batch: {}", jobId, batchId)
                }
            } else null

            // Submit KG extraction batch if we have requests
            val kgBatchId = if (kgRequests.isNotEmpty()) {
                geminiBatchService.createContentBatch(kgRequests.map { it.request }).also { batchId ->
                    job.addBatchJob(batchId)
                    logger.info("[{}] Submitted KG extraction batch: {}", jobId, batchId)
                }
            } else null

            batchJobRepository.update(job)

            // Poll both batches in parallel
            coroutineScope {
                val embeddingDeferred = embeddingBatchId?.let { batchId ->
                    async {
                        pollingService.pollUntilComplete(job, eventFlow, batchId, "embedding")
                    }
                }
                val kgDeferred = kgBatchId?.let { batchId ->
                    async {
                        pollingService.pollUntilComplete(job, eventFlow, batchId, "KG extraction")
                    }
                }

                listOfNotNull(embeddingDeferred, kgDeferred).awaitAll()
            }

            // Fetch embedding results
            embeddingBatchId?.let { batchId ->
                fetchEmbeddingResults(batchId, embeddingRequests, embeddingRequestMap, embeddingResults, jobId)
            }

            // Fetch KG extraction results
            kgBatchId?.let { batchId ->
                fetchKgResults(batchId, kgRequests, kgResults, jobId)
            }

            logger.info(
                "[{}] Successfully retrieved {} embeddings and {} KG extractions",
                jobId, embeddingResults.size, kgResults.size
            )

        } catch (e: Exception) {
            logger.error("[{}] Parallel batch processing failed: {}", jobId, e.message, e)
            // Continue with whatever results we have
        } finally {
            job.clearBatchJobs()
            batchJobRepository.update(job)
        }

        return Pair(embeddingResults, kgResults)
    }

    private suspend fun fetchEmbeddingResults(
        batchId: String,
        embeddingRequests: List<io.deepsearch.domain.services.BatchEmbeddingRequest>,
        embeddingRequestMap: Map<String, String>,
        embeddingResults: MutableMap<String, List<Float>>,
        jobId: Long
    ) {
        try {
            val batchResults = geminiBatchService.fetchBatchResults(batchId)
            logger.info("[{}] Retrieved {} embedding batch results", jobId, batchResults.size)

            // Record token usage
            batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "PageEmbeddingBatch", "gemini-embedding-001", batchResults)

            batchResults.forEachIndexed { index, result ->
                val requestId = embeddingRequests.getOrNull(index)?.requestId ?: return@forEachIndexed
                val url = embeddingRequestMap[requestId] ?: return@forEachIndexed

                if (result.success && result.embedding != null) {
                    embeddingResults[url] = result.embedding!!
                } else {
                    logger.warn("[{}] Embedding failed for {}: {}", jobId, url, result.errorMessage)
                }
            }
        } catch (e: Exception) {
            logger.warn("[{}] Failed to fetch embedding results: {}", jobId, e.message)
        }
    }

    private suspend fun fetchKgResults(
        batchId: String,
        kgRequests: List<KgBatchRequest>,
        kgResults: MutableMap<Long, KgExtractionResult>,
        jobId: Long
    ) {
        try {
            val batchResults = geminiBatchService.fetchBatchResults(batchId)
            logger.info("[{}] Retrieved {} KG batch results", jobId, batchResults.size)

            // Record token usage
            val kgModelId = kgRequests.firstOrNull()?.request?.modelId ?: "unknown"
            batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "KgExtractionBatch", kgModelId, batchResults)

            batchResults.forEachIndexed { index, result ->
                val kgRequest = kgRequests.getOrNull(index) ?: return@forEachIndexed

                try {
                    if (!result.success || result.generatedText == null) {
                        logger.warn(
                            "[{}] KG batch failed for {}: {}",
                            jobId, kgRequest.url, result.errorMessage
                        )
                        return@forEachIndexed
                    }

                    val extraction = knowledgeGraphIndexingService.parseBatchResponse(result.generatedText!!)
                    if (!extraction.isEmpty()) {
                        kgResults[kgRequest.urlStateId] = extraction
                    } else {
                        logger.debug("[{}] No entities extracted from {}", jobId, kgRequest.url)
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "[{}] Failed to parse KG result for {}: {}",
                        jobId, kgRequest.url, e.message
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("[{}] Failed to fetch KG results: {}", jobId, e.message)
        }
    }

    private suspend fun cacheUrlsAndStoreKgResults(
        urlDataMap: Map<Long, UrlCacheData>,
        kgResults: Map<Long, KgExtractionResult>,
        sessionId: PeriodicIndexSessionId,
        job: BatchPeriodicIndexJob
    ) {
        val jobId = job.id!!
        
        urlDataMap.values.forEach { data ->
            try {
                // Store KG extraction result in GCS for Stage 5
                val kgResult = kgResults[data.urlState.id!!]
                if (kgResult != null) {
                    snapshotStorage.storeKgExtractionResult(data.basePath, kgResult)
                }

                webpageCacheService.cacheWebpageBatch(
                    url = data.urlState.url,
                    title = data.urlState.title,
                    description = data.urlState.description,
                    markdown = data.markdown,
                    html = data.cachingData?.html,
                    httpStatus = 200,
                    httpReason = "OK",
                    mimeType = "text/html",
                    sessionId = sessionId,
                    imageMapping = data.imageMapping.takeIf { it.isNotEmpty() }
                )

                data.urlState.markCached()
                batchUrlStateRepository.update(data.urlState)
                job.urlsCached++
            } catch (e: Exception) {
                logger.warn("[{}] Failed to cache {}: {}", jobId, data.urlState.url, e.message)
                data.urlState.markFailed(e.message ?: "Caching failed")
                batchUrlStateRepository.update(data.urlState)
            }
        }
    }

    /**
     * Builds final markdown from cached data, matching WebpageExtractionService.processDom exactly.
     * 
     * Processing steps (matching WebpageExtractionService):
     * 1. Apply media replacements with PLACEHOLDERS
     * 2. Extract popup text before removal
     * 3. Remove semantic elements
     * 3.5. Remove hidden mobile layout elements
     * 4. Interpret and replace tables (using placeholders)
     * 5. Clean up DOM before markdown conversion
     * 6. Convert HTML to Markdown
     * 7. Replace placeholders with actual text
     * 8. Add metadata header and popup content
     */
    private fun buildFinalMarkdown(
        urlState: BatchUrlState,
        cachingData: CachingData?,
        icons: List<MediaFileData>,
        images: List<MediaFileData>
    ): MarkdownBuildResult {
        if (cachingData == null) {
            val markdown = buildString {
                appendLine("URL: ${urlState.url}")
                appendLine("Title: ${urlState.title ?: "Unknown"}")
                appendLine()
                appendLine("Content extracted via batch processing.")
            }
            return MarkdownBuildResult(markdown, emptyMap())
        }

        // HTML already has data-ds-id attributes from browser's injectStableIds()
        val html = cachingData.cleanedHtml ?: cachingData.html
        val doc = Jsoup.parse(html)

        // ===== Step 1: Apply media replacements with PLACEHOLDERS =====
        // Use placeholders instead of actual text to prevent markdown syntax escaping
        // during HTML-to-Markdown conversion
        val mediaResult = MediaReplacementBuilder.buildFromIconsAndImages(
            icons,
            images,
            cachingData.iconInterpretations,
            cachingData.imageTexts
        )
        val placeholderMap = if (mediaResult.replacements.isNotEmpty()) {
            jsoupDomService.replaceElementsWithPlaceholders(doc, mediaResult.replacements).toMutableMap()
        } else {
            mutableMapOf()
        }

        // ===== Step 2: Extract popup text before removal =====
        val popupText = cachingData.semanticElements?.popups
            ?.map { "[data-ds-id=\"${it.dataId}\"]" }
            ?.takeIf { it.isNotEmpty() }
            ?.let { selectors ->
                jsoupDomService.extractElementsText(doc, selectors)
                    .values
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }
            }

        // ===== Step 3: Remove semantic elements =====
        cachingData.semanticElements?.let { semantic ->
            val semanticSelectors = listOfNotNull(
                semantic.header?.dataId,
                semantic.footer?.dataId,
                semantic.navSidebar?.dataId,
                semantic.breadcrumb?.dataId,
                semantic.cookieBanner?.dataId
            ).plus(semantic.adBanners.map { it.dataId })
                .plus(semantic.popups.map { it.dataId })
                .map { "[data-ds-id=\"$it\"]" }
            jsoupDomService.removeElements(doc, semanticSelectors)
        }

        // ===== Step 4: Replace tables with markdown (using placeholders) =====
        val tableReplacements = cachingData.tableMarkdowns?.map { (dataId, markdown) ->
            CssSelectorReplacement("[data-ds-id=\"$dataId\"]", markdown)
        } ?: emptyList()
        if (tableReplacements.isNotEmpty()) {
            // Use placeholders for tables too - this prevents markdown newlines from being escaped
            // during HTML-to-Markdown conversion. Use TABLE prefix to avoid ID collisions.
            val tablePlaceholders = jsoupDomService.replaceElementsWithPlaceholders(
                doc, tableReplacements, PlaceholderPrefix.TABLE
            )
            placeholderMap.putAll(tablePlaceholders)
        }

        // ===== Step 5: Clean up DOM before markdown conversion =====
        // Remove empty elements that would produce markdown artifacts like orphan `>` or `* `
        val cleanupStats = jsoupDomService.cleanupForMarkdownConversion(doc)
        if (cleanupStats.emptyListItemsRemoved > 0 || cleanupStats.emptyBlockquoteChildrenRemoved > 0) {
            logger.debug(
                "Markdown pre-cleanup: {} list items, {} blockquote children, {} elements",
                cleanupStats.emptyListItemsRemoved,
                cleanupStats.emptyBlockquoteChildrenRemoved,
                cleanupStats.emptyElementsRemoved
            )
        }

        // ===== Step 6: Convert HTML to Markdown =====
        // Placeholders pass through cleanly without escaping
        val rawMarkdown = htmlToMarkdownService.convert(doc.html())

        // ===== Step 7: Replace placeholders with actual text =====
        var finalMarkdown = rawMarkdown
        placeholderMap.values.forEach { mapping ->
            finalMarkdown = finalMarkdown.replace(mapping.placeholder, mapping.text)
        }

        // ===== Step 8: Add metadata header and popup content =====
        val markdown = buildString {
            appendLine("URL: ${urlState.url}")
            if (!urlState.title.isNullOrBlank()) {
                appendLine("Title: ${urlState.title}")
            }
            if (!urlState.description.isNullOrBlank()) {
                appendLine("Description: ${urlState.description}")
            }
            appendLine()
            append(finalMarkdown)
            // Append popup text at the end if present (dialogs, tooltips, etc.)
            if (!popupText.isNullOrBlank()) {
                appendLine()
                appendLine("---")
                appendLine("## Popup Content")
                appendLine()
                append(popupText)
            }
        }

        return MarkdownBuildResult(markdown, mediaResult.imageMapping)
    }
}
