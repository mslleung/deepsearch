package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IHybridSearchIndexingService
import io.deepsearch.application.services.IKnowledgeGraphIndexingService
import io.deepsearch.application.services.IWebpageCacheService
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchContentRequest
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.CachingData
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import io.deepsearch.domain.services.MediaFileData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
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
    private val webpageCacheService: IWebpageCacheService,
    private val hybridSearchIndexingService: IHybridSearchIndexingService,
    private val knowledgeGraphIndexingService: IKnowledgeGraphIndexingService,
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val BATCH_POLL_INTERVAL_MS = 60_000L
        private const val MAX_BATCH_POLL_ATTEMPTS = 1440
    }

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 4: Parallel embedding and KG extraction (HTML only)", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 4: Generating embeddings & extracting knowledge graph...")

        // Get HTML URLs only (FINAL_LLM_DONE)
        // FILE URLs are processed independently by FileUploadBackgroundWorker
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
        val urlDataMap = mutableMapOf<Long, UrlCacheData>()

        htmlUrlsNeedingCaching.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach
                
                // Read caching data from GCS
                val cachingData = snapshotStorage.readForCaching(basePath)
                val icons = snapshotStorage.readIcons(basePath)
                val images = snapshotStorage.readImages(basePath)

                val markdown = buildFinalMarkdown(urlState, cachingData, icons, images)
                urlDataMap[urlState.id!!] = UrlCacheData(
                    urlState = urlState,
                    basePath = basePath,
                    markdown = markdown,
                    cachingData = cachingData,
                    icons = icons,
                    images = images
                )
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare markdown for HTML {}: {}", jobId, urlState.url, e.message)
            }
        }

        // Step 2: Prepare batch requests for both embeddings and KG extraction
        val embeddingRequestMap = mutableMapOf<String, String>() // requestId -> URL
        val embeddingRequests = urlDataMap.mapNotNull { (urlStateId, data) ->
            val requestId = "${jobId}-${urlStateId}-embed"
            embeddingRequestMap[requestId] = data.urlState.url
            hybridSearchIndexingService.prepareBatchRequest(requestId, data.markdown)
        }

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
                kgRequests.add(KgBatchRequest(
                    urlStateId = urlStateId,
                    url = data.urlState.url,
                    request = request
                ))
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare KG request for {}: {}", jobId, data.urlState.url, e.message)
            }
        }

        logger.info("[{}] Prepared {} embedding requests and {} KG requests", 
            jobId, embeddingRequests.size, kgRequests.size)

        // Step 3: Submit both batches and poll in parallel
        val embeddingResults = mutableMapOf<String, List<Float>>() // URL -> embedding
        val kgResults = mutableMapOf<Long, KgExtractionResult>() // urlStateId -> extraction result

        try {
            eventEmitter.emit(job, eventFlow, 
                "Submitting parallel batches: ${embeddingRequests.size} embeddings, ${kgRequests.size} KG extractions...")

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
                        pollBatchUntilComplete(job, eventFlow, batchId, "embedding")
                    }
                }
                val kgDeferred = kgBatchId?.let { batchId ->
                    async {
                        pollBatchUntilComplete(job, eventFlow, batchId, "KG extraction")
                    }
                }

                listOfNotNull(embeddingDeferred, kgDeferred).awaitAll()
            }

            // Fetch embedding results
            embeddingBatchId?.let { batchId ->
                try {
                    val batchResults = geminiBatchService.fetchBatchResults(batchId)
                    logger.info("[{}] Retrieved {} embedding batch results", jobId, batchResults.size)
                    
                    // Record token usage for embedding batch (embeddings are free but we track for completeness)
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

            // Fetch KG extraction results
            kgBatchId?.let { batchId ->
                try {
                    val batchResults = geminiBatchService.fetchBatchResults(batchId)
                    logger.info("[{}] Retrieved {} KG batch results", jobId, batchResults.size)
                    
                    // Record token usage for KG extraction batch
                    val kgModelId = kgRequests.firstOrNull()?.request?.modelId ?: "unknown"
                    batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "KgExtractionBatch", kgModelId, batchResults)

                    batchResults.forEachIndexed { index, result ->
                        val kgRequest = kgRequests.getOrNull(index) ?: return@forEachIndexed

                        try {
                            if (!result.success || result.generatedText == null) {
                                logger.warn("[{}] KG batch failed for {}: {}", 
                                    jobId, kgRequest.url, result.errorMessage)
                                return@forEachIndexed
                            }

                            val extraction = knowledgeGraphIndexingService.parseBatchResponse(result.generatedText!!)
                            if (!extraction.isEmpty()) {
                                kgResults[kgRequest.urlStateId] = extraction
                            } else {
                                logger.debug("[{}] No entities extracted from {}", jobId, kgRequest.url)
                            }
                        } catch (e: Exception) {
                            logger.warn("[{}] Failed to parse KG result for {}: {}", 
                                jobId, kgRequest.url, e.message)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[{}] Failed to fetch KG results: {}", jobId, e.message)
                }
            }

            logger.info("[{}] Successfully retrieved {} embeddings and {} KG extractions", 
                jobId, embeddingResults.size, kgResults.size)

        } catch (e: Exception) {
            logger.error("[{}] Parallel batch processing failed: {}", jobId, e.message, e)
            // Continue with whatever results we have
        } finally {
            job.clearBatchJobs()
            batchJobRepository.update(job)
        }

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
                    sessionId = sessionId
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

        batchJobRepository.update(job)

        val entityCount = kgResults.values.sumOf { it.entities.size }
        val relationshipCount = kgResults.values.sumOf { it.relationships.size }

        job.advanceToNextStage()
        batchJobRepository.update(job)
        eventEmitter.emit(job, eventFlow, 
            "Stage 4 complete: ${job.urlsCached} pages cached, ${embeddingResults.size} embeddings, " +
            "$entityCount entities, $relationshipCount relationships")
    }

    private data class UrlCacheData(
        val urlState: BatchUrlState,
        val basePath: String,
        val markdown: String,
        val cachingData: CachingData?,
        val icons: List<MediaFileData>,
        val images: List<MediaFileData>
    )

    private data class KgBatchRequest(
        val urlStateId: Long,
        val url: String,
        val request: BatchContentRequest
    )

    private fun buildFinalMarkdown(
        urlState: BatchUrlState, 
        cachingData: CachingData?,
        icons: List<MediaFileData>,
        images: List<MediaFileData>
    ): String {
        if (cachingData == null) {
            return buildString {
                appendLine("URL: ${urlState.url}")
                appendLine("Title: ${urlState.title ?: "Unknown"}")
                appendLine()
                appendLine("Content extracted via batch processing.")
            }
        }

        val html = cachingData.cleanedHtml ?: cachingData.html
        val doc = Jsoup.parse(html)

        // Step 1: Inject media identifiers
        jsoupDomService.injectMediaIdentifiers(doc)

        // Step 2: Apply icon/image replacements
        val mediaReplacements = buildMediaReplacements(cachingData, icons, images)
        if (mediaReplacements.isNotEmpty()) {
            jsoupDomService.replaceElementsWithText(doc, mediaReplacements)
        }

        // Step 3: Extract popup text before removal (matches WebpageExtractionService behavior)
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

        // Step 4: Remove semantic elements
        cachingData.semanticElements?.let { semantic ->
            listOfNotNull(
                semantic.header?.dataId,
                semantic.footer?.dataId,
                semantic.navSidebar?.dataId,
                semantic.breadcrumb?.dataId,
                semantic.cookieBanner?.dataId
            ).plus(semantic.adBanners.map { it.dataId })
                .plus(semantic.popups.map { it.dataId })
                .forEach { dataId ->
                    doc.select("[data-ds-id=\"$dataId\"]").remove()
                }
        }

        // Step 5: Replace tables with markdown (using replaceElementsWithText for consistency)
        val tableReplacements = cachingData.tableMarkdowns?.map { (dataId, markdown) ->
            CssSelectorReplacement("[data-ds-id=\"$dataId\"]", markdown)
        } ?: emptyList()
        if (tableReplacements.isNotEmpty()) {
            jsoupDomService.replaceElementsWithText(doc, tableReplacements)
        }

        val textContent = jsoupDomService.extractTextContent(doc)

        return buildString {
            appendLine("URL: ${urlState.url}")
            appendLine("Title: ${urlState.title ?: "Unknown"}")
            if (!urlState.description.isNullOrBlank()) {
                appendLine("Description: ${urlState.description}")
            }
            appendLine()
            if (!popupText.isNullOrBlank()) {
                appendLine(popupText)
                appendLine()
            }
            appendLine(textContent)
        }.trim()
    }

    private fun buildMediaReplacements(
        cachingData: CachingData, 
        icons: List<MediaFileData>,
        images: List<MediaFileData>
    ): List<CssSelectorReplacement> {
        val replacements = mutableListOf<CssSelectorReplacement>()

        icons.forEach { iconData ->
            val label = cachingData.iconInterpretations?.get(iconData.hash)
            if (label != null) {
                iconData.cssSelectors.forEach { selector ->
                    replacements.add(CssSelectorReplacement(selector, label))
                }
            }
        }

        images.forEach { imageData ->
            val text = cachingData.imageTexts?.get(imageData.hash)
            if (text != null) {
                val imageId = generateImageId(imageData.hash)
                val wrappedText = if (text.contains('\n')) {
                    "<image id=\"$imageId\">\n$text\n</image>"
                } else {
                    "<image id=\"$imageId\">$text</image>"
                }
                imageData.cssSelectors.forEach { selector ->
                    replacements.add(CssSelectorReplacement(selector, wrappedText))
                }
            }
        }

        return replacements
    }

    private fun generateImageId(hash: String): String {
        val urlSafeHash = hash.replace("+", "-").replace("/", "_").trimEnd('=')
        return "img-$urlSafeHash"
    }

    private suspend fun pollBatchUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchJobId: String,
        batchType: String
    ) {
        var attempts = 0

        while (attempts < MAX_BATCH_POLL_ATTEMPTS) {
            try {
                val status = geminiBatchService.pollBatchStatus(batchJobId)

                when (status.state) {
                    BatchJobState.SUCCEEDED -> {
                        logger.info("{} batch job {} completed successfully", batchType, batchJobId)
                        return
                    }

                    BatchJobState.FAILED -> throw RuntimeException("$batchType batch job failed: ${status.errorMessage}")
                    BatchJobState.CANCELLED -> throw RuntimeException("$batchType batch job was cancelled")
                    else -> {
                        eventEmitter.emit(
                            job,
                            eventFlow,
                            "Waiting for $batchType batch (${status.completedRequests}/${status.totalRequests})"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling {}, will retry", batchType)
                } else {
                    throw e
                }
            }

            delay(BATCH_POLL_INTERVAL_MS)
            attempts++
        }

        throw RuntimeException("$batchType batch job polling timed out after $MAX_BATCH_POLL_ATTEMPTS attempts")
    }
}

