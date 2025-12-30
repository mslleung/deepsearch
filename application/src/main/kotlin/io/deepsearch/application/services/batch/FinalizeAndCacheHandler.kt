package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IHybridSearchIndexingService
import io.deepsearch.application.services.IWebpageCacheService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stage 4: Finalize and cache handler.
 * 
 * Finalizes markdown from processed HTML and generates embeddings via Gemini Batch API.
 * Caches the results to the webpage cache service.
 */
class FinalizeAndCacheHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val jsoupDomService: IJsoupDomService,
    private val webpageCacheService: IWebpageCacheService,
    private val hybridSearchIndexingService: IHybridSearchIndexingService,
    private val eventEmitter: BatchEventEmitter
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
        logger.info("[{}] Stage 4: Finalizing and caching", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 4: Saving results to cache...")

        val urlsNeedingCaching = batchUrlStateRepository.findNeedingCaching(jobId)
        logger.info("[{}] {} URLs need caching", jobId, urlsNeedingCaching.size)

        if (urlsNeedingCaching.isEmpty()) {
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 4 complete: No URLs to cache")
            return
        }

        val sessionId = PeriodicIndexSessionId(jobId)

        // Step 1: Prepare markdown for each URL
        val urlDataMap = mutableMapOf<Long, UrlCacheData>()

        urlsNeedingCaching.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                }

                val markdown = buildFinalMarkdown(urlState, snapshotData)
                urlDataMap[urlState.id!!] = UrlCacheData(
                    urlState = urlState,
                    markdown = markdown,
                    snapshotData = snapshotData
                )
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare markdown for {}: {}", jobId, urlState.url, e.message)
            }
        }

        // Step 2: Prepare embedding batch requests using the indexing service
        val embeddingRequestMap = mutableMapOf<String, String>() // requestId -> URL
        val embeddingRequests = urlDataMap.mapNotNull { (urlStateId, data) ->
            val requestId = "${jobId}-${urlStateId}-embed"
            embeddingRequestMap[requestId] = data.urlState.url
            hybridSearchIndexingService.prepareBatchRequest(requestId, data.markdown)
        }

        // Step 3: Submit embedding batch and fetch results
        val embeddingResults = mutableMapOf<String, List<Float>>() // URL -> embedding
        if (embeddingRequests.isNotEmpty()) {
            try {
                eventEmitter.emit(job, eventFlow, "Generating embeddings for ${embeddingRequests.size} pages...")

                val embeddingBatchId = geminiBatchService.createEmbeddingBatch(embeddingRequests)
                job.setBatchJob(embeddingBatchId)
                batchJobRepository.update(job)

                logger.info("[{}] Submitted embedding batch: {}", jobId, embeddingBatchId)

                try {
                    pollBatchUntilComplete(job, eventFlow, embeddingBatchId)

                    // Fetch batch results
                    val batchResults = geminiBatchService.fetchBatchResults(embeddingBatchId)
                    logger.info("[{}] Retrieved {} embedding batch results", jobId, batchResults.size)

                    // Map results back to URLs
                    batchResults.forEachIndexed { index, result ->
                        val requestId = embeddingRequests.getOrNull(index)?.requestId ?: return@forEachIndexed
                        val url = embeddingRequestMap[requestId] ?: return@forEachIndexed

                        if (result.success && result.embedding != null) {
                            embeddingResults[url] = result.embedding!!
                        } else {
                            logger.warn("[{}] Embedding failed for {}: {}", jobId, url, result.errorMessage)
                        }
                    }

                    logger.info("[{}] Successfully retrieved {} embeddings", jobId, embeddingResults.size)

                } catch (e: Exception) {
                    logger.warn("[{}] Embedding batch failed, continuing without embeddings: {}", jobId, e.message)
                } finally {
                    job.clearBatchJob()
                    batchJobRepository.update(job)
                }
            } catch (e: Exception) {
                logger.warn("[{}] Embedding batch failed: {}, continuing without embeddings", jobId, e.message)
            }
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

        // Step 5: Cache all pages (without triggering async indexing - handled by batch stages)
        urlDataMap.values.forEach { data ->
            try {
                webpageCacheService.cacheWebpageBatch(
                    url = data.urlState.url,
                    title = data.urlState.title,
                    description = data.urlState.description,
                    markdown = data.markdown,
                    html = data.snapshotData?.html,
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

        job.advanceToNextStage()
        batchJobRepository.update(job)
        eventEmitter.emit(job, eventFlow, "Stage 4 complete: ${job.urlsCached} pages cached, ${embeddingResults.size} embeddings")
    }

    private data class UrlCacheData(
        val urlState: BatchUrlState,
        val markdown: String,
        val snapshotData: BatchUrlSnapshotData?
    )

    private fun buildFinalMarkdown(urlState: BatchUrlState, snapshotData: BatchUrlSnapshotData?): String {
        if (snapshotData == null) {
            return buildString {
                appendLine("URL: ${urlState.url}")
                appendLine("Title: ${urlState.title ?: "Unknown"}")
                appendLine()
                appendLine("Content extracted via batch processing.")
            }
        }

        val existingMarkdown = snapshotData.markdown
        if (existingMarkdown != null) {
            return existingMarkdown
        }

        val html = snapshotData.cleanedHtml ?: snapshotData.html
        val doc = Jsoup.parse(html)

        // Step 1: Inject media identifiers
        jsoupDomService.injectMediaIdentifiers(doc)

        // Step 2: Apply icon/image replacements
        val mediaReplacements = buildMediaReplacements(snapshotData)
        if (mediaReplacements.isNotEmpty()) {
            jsoupDomService.replaceElementsWithText(doc, mediaReplacements)
        }

        // Step 3: Extract popup text before removal (matches WebpageExtractionService behavior)
        val popupText = snapshotData.semanticElements?.popups
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
        snapshotData.semanticElements?.let { semantic ->
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
        val tableReplacements = snapshotData.tableMarkdowns?.map { (dataId, markdown) ->
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

    private fun buildMediaReplacements(snapshotData: BatchUrlSnapshotData): List<CssSelectorReplacement> {
        val replacements = mutableListOf<CssSelectorReplacement>()

        snapshotData.icons?.forEach { iconData ->
            val label = snapshotData.iconInterpretations?.get(iconData.hashBase64)
            if (label != null) {
                iconData.cssSelectors.forEach { selector ->
                    replacements.add(CssSelectorReplacement(selector, label))
                }
            }
        }

        snapshotData.images?.forEach { imageData ->
            val text = snapshotData.imageTexts?.get(imageData.hashBase64)
            if (text != null) {
                val imageId = generateImageId(imageData.hashBase64)
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

    private fun generateImageId(hashBase64: String): String {
        val urlSafeHash = hashBase64.replace("+", "-").replace("/", "_").trimEnd('=')
        return "img-$urlSafeHash"
    }

    private suspend fun pollBatchUntilComplete(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        batchJobId: String
    ) {
        var attempts = 0

        while (attempts < MAX_BATCH_POLL_ATTEMPTS) {
            try {
                val status = geminiBatchService.pollBatchStatus(batchJobId)

                when (status.state) {
                    BatchJobState.SUCCEEDED -> {
                        logger.info("Batch job {} completed successfully", batchJobId)
                        return
                    }

                    BatchJobState.FAILED -> throw RuntimeException("Batch job failed: ${status.errorMessage}")
                    BatchJobState.CANCELLED -> throw RuntimeException("Batch job was cancelled")
                    else -> {
                        eventEmitter.emit(
                            job,
                            eventFlow,
                            "Waiting for batch (${status.completedRequests}/${status.totalRequests})"
                        )
                    }
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    logger.warn("Rate limited while polling, will retry")
                } else {
                    throw e
                }
            }

            delay(BATCH_POLL_INTERVAL_MS)
            attempts++
        }

        throw RuntimeException("Batch job polling timed out after $MAX_BATCH_POLL_ATTEMPTS attempts")
    }
}
