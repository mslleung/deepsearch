package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.services.IGeminiBatchService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Phase 5 of ContentLlmBatchHandler: Process media (icon/image) batch results.
 * Handles icon interpretation and image classification results, including
 * follow-up table extraction for images containing tables.
 */
class MediaResultProcessor(
    private val geminiBatchService: IGeminiBatchService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService,
    private val pollingService: BatchPollingService,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Process media batch results (icons and images).
     *
     * @param batches Submitted batch IDs
     * @param preparations Batch preparations with cached results and pending requests
     * @param job Batch periodic index job
     * @param eventFlow Event flow for progress updates
     * @param jobId Job ID for logging
     * @return Processed media results (icon interpretations and image texts)
     */
    suspend fun process(
        batches: SubmittedBatches,
        preparations: ContentBatchPreparer.BatchPreparations,
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        jobId: Long
    ): MediaResults {
        val iconInterpretations = preparations.iconPrep.cachedResults.toMutableMap()
        val imageTexts = preparations.imagePrep.cachedResults.toMutableMap()

        // Process icon batch results
        val newIconResults = processIconBatch(batches, preparations, jobId)
        iconInterpretations.putAll(newIconResults)
        if (newIconResults.isNotEmpty()) {
            webpageIconInterpretationService.processBatchResults(newIconResults)
        }

        // Process image classification batch results
        val (newImageResults, imagesWithTables) = processImageClassificationBatch(batches, preparations, jobId)
        imageTexts.putAll(newImageResults)

        // Handle images containing tables (follow-up extraction)
        if (imagesWithTables.isNotEmpty()) {
            val tableExtractionResults = processImageTableExtraction(
                imagesWithTables, preparations, job, eventFlow, jobId
            )
            imageTexts.putAll(tableExtractionResults)
            newImageResults.putAll(tableExtractionResults)
        }

        // Cache new image results
        if (newImageResults.isNotEmpty()) {
            webpageImageTextExtractionService.processBatchResults(newImageResults, preparations.imageDataMap)
        }

        return MediaResults(iconInterpretations, imageTexts)
    }

    private suspend fun processIconBatch(
        batches: SubmittedBatches,
        preparations: ContentBatchPreparer.BatchPreparations,
        jobId: Long
    ): MutableMap<MediaHash, String?> {
        val newIconResults = mutableMapOf<MediaHash, String?>()
        
        if (batches.iconId == null) return newIconResults

        val iconResults = geminiBatchService.fetchBatchResults(batches.iconId)

        // Record token usage
        val iconModelId = preparations.iconPrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
        batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "IconInterpretationBatch", iconModelId, iconResults)

        iconResults.forEachIndexed { index, result ->
            val pending = preparations.iconPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
            if (result.success && result.generatedText != null) {
                val label = parseIconResponse(result.generatedText!!, jobId)
                newIconResults[pending.hash] = label
            }
        }

        logger.info(
            "[{}] Processed {} icon interpretations ({} cached, {} new)",
            jobId, preparations.iconPrep.cachedResults.size + newIconResults.size,
            preparations.iconPrep.cachedResults.size, newIconResults.size
        )

        return newIconResults
    }

    private suspend fun processImageClassificationBatch(
        batches: SubmittedBatches,
        preparations: ContentBatchPreparer.BatchPreparations,
        jobId: Long
    ): Pair<MutableMap<MediaHash, String?>, MutableMap<MediaHash, MediaData>> {
        val newImageResults = mutableMapOf<MediaHash, String?>()
        val imagesWithTables = mutableMapOf<MediaHash, MediaData>()

        if (batches.imageClassId == null) return Pair(newImageResults, imagesWithTables)

        val imageResults = geminiBatchService.fetchBatchResults(batches.imageClassId)

        // Record token usage
        val imageModelId = preparations.imagePrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
        batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "ImageClassificationBatch", imageModelId, imageResults)

        imageResults.forEachIndexed { index, result ->
            val pending = preparations.imagePrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
            if (result.success && result.generatedText != null) {
                val classification = parseImageClassificationResponse(result.generatedText!!, jobId)
                if (classification?.needsTableInterpretation == true) {
                    preparations.imageDataMap[pending.hash]?.let { imagesWithTables[pending.hash] = it }
                } else {
                    val text = classification?.text?.takeIf { it.isNotBlank() }
                    newImageResults[pending.hash] = text
                }
            }
        }

        logger.info(
            "[{}] Processed image classifications: {} with text, {} need table extraction",
            jobId, newImageResults.size, imagesWithTables.size
        )

        return Pair(newImageResults, imagesWithTables)
    }

    private suspend fun processImageTableExtraction(
        imagesWithTables: Map<MediaHash, MediaData>,
        preparations: ContentBatchPreparer.BatchPreparations,
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        jobId: Long
    ): MutableMap<MediaHash, String?> {
        val results = mutableMapOf<MediaHash, String?>()

        val tableExtractPrep = webpageImageTextExtractionService.prepareTableExtractionBatchRequests(imagesWithTables, jobId)

        if (tableExtractPrep.pendingRequests.isEmpty()) return results

        val tableExtractBatchId = geminiBatchService.createContentBatch(tableExtractPrep.pendingRequests.map { it.request })
        logger.info("[{}] Submitted image table extraction batch: {}", jobId, tableExtractBatchId)

        pollingService.pollUntilComplete(job, eventFlow, tableExtractBatchId, "image table extraction")

        val extractResults = geminiBatchService.fetchBatchResults(tableExtractBatchId)

        // Record token usage
        val tableExtractModelId = tableExtractPrep.pendingRequests.firstOrNull()?.request?.modelId ?: "unknown"
        batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "ImageTableExtractionBatch", tableExtractModelId, extractResults)

        extractResults.forEachIndexed { index, result ->
            val pending = tableExtractPrep.pendingRequests.getOrNull(index) ?: return@forEachIndexed
            if (result.success && result.generatedText != null) {
                val extraction = parseTableExtractionResponse(result.generatedText!!, jobId)
                val text = extraction?.text?.takeIf { it.isNotBlank() }
                results[pending.hash] = text
            }
        }

        logger.info("[{}] Extracted tables from {} images", jobId, extractResults.count { it.success })

        return results
    }

    // Response parsing helpers

    private fun parseIconResponse(responseText: String, jobId: Long): String? {
        return try {
            val parsed = json.decodeFromString<IconBatchResponseWrapper>(responseText)
            parsed.icons.firstOrNull()?.label
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse icon response: {}", jobId, e.message)
            null
        }
    }

    private fun parseImageClassificationResponse(responseText: String, jobId: Long): ImageClassificationResponseWrapper? {
        return try {
            json.decodeFromString<ImageClassificationResponseWrapper>(responseText)
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse image classification: {}", jobId, e.message)
            null
        }
    }

    private fun parseTableExtractionResponse(responseText: String, jobId: Long): TableExtractionResponseWrapper? {
        return try {
            json.decodeFromString<TableExtractionResponseWrapper>(responseText)
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse table extraction: {}", jobId, e.message)
            null
        }
    }
}
