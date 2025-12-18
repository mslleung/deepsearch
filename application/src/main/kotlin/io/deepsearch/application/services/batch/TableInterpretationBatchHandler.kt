package io.deepsearch.application.services.batch

import io.deepsearch.application.services.ITableInterpretationService
import io.deepsearch.application.services.TableInterpretationBatchInput
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlSnapshotData
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.BatchJobState
import io.deepsearch.domain.services.CssSelectorReplacement
import io.deepsearch.domain.services.IBoundingBoxDerivationService
import io.deepsearch.domain.services.IGeminiBatchService
import io.deepsearch.domain.services.IJsoupDomService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 3: LLM Table Interpretation batch handler.
 * 
 * Applies icon/image replacements, then submits batch job for table interpretation.
 * Tables are interpreted with context from the surrounding HTML (with media replacements applied).
 */
class TableInterpretationBatchHandler(
    private val batchJobRepository: IBatchPeriodicIndexJobRepository,
    private val batchUrlStateRepository: IBatchUrlStateRepository,
    private val geminiBatchService: IGeminiBatchService,
    private val jsoupDomService: IJsoupDomService,
    private val boundingBoxDerivationService: IBoundingBoxDerivationService,
    private val tableInterpretationService: ITableInterpretationService,
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

    // Mapping storage for batch result processing
    private val tableInterpretationMappings = ConcurrentHashMap<Long, List<Triple<Long, String, Int>>>()

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 3: LLM table interpretation", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 3: Applying media replacements and preparing table interpretation...")

        // Check if we already have a batch job running (resume case)
        if (job.geminiBatchJobId != null) {
            pollBatchUntilComplete(job, eventFlow, job.geminiBatchJobId!!)
            processTableBatchResults(jobId, job.geminiBatchJobId!!)
            job.clearBatchJob()
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        val urlsNeedingProcessing = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)
        if (urlsNeedingProcessing.isEmpty()) {
            logger.info("[{}] No URLs need table interpretation, advancing", jobId)
            job.advanceToNextStage()
            batchJobRepository.update(job)
            return
        }

        // Collect table interpretation inputs
        val tableInputs = collectTableInputs(urlsNeedingProcessing, jobId)

        // Use service to prepare batch requests (with cache check)
        val tableBatchPrep = tableInterpretationService.prepareBatchRequests(tableInputs, jobId)
        val batchRequests = tableBatchPrep.batchRequests
        val cachedTableResults = tableBatchPrep.cachedResults

        // Apply cached results immediately
        applyCachedResults(cachedTableResults, urlsNeedingProcessing)

        if (batchRequests.isEmpty()) {
            markAllUrlsAsDone(urlsNeedingProcessing)
            job.urlsFinalProcessed = urlsNeedingProcessing.size
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 3 complete: All tables cached or no tables to interpret")
            return
        }

        // Store mapping for result processing
        val requestMapping = tableBatchPrep.requestIndexToKey.entries.map { (index, key) ->
            Triple(key.first, key.second, index)
        }
        tableInterpretationMappings[jobId] = requestMapping

        logger.info(
            "[{}] Submitting {} table interpretation requests ({} cached)",
            jobId, batchRequests.size, cachedTableResults.size
        )

        try {
            val batchJobId = geminiBatchService.createContentBatch(batchRequests)
            job.setBatchJob(batchJobId)
            batchJobRepository.update(job)

            logger.info("[{}] Submitted table batch job: {}", jobId, batchJobId)
            eventEmitter.emit(job, eventFlow, "Table batch submitted: $batchJobId (${batchRequests.size} tables)")

            pollBatchUntilComplete(job, eventFlow, batchJobId)
            processTableBatchResults(jobId, batchJobId)

            job.clearBatchJob()
            job.advanceToNextStage()
            batchJobRepository.update(job)
        } catch (e: Exception) {
            logger.error("[{}] Failed to submit table batch: {}", jobId, e.message, e)
            throw e
        }
    }

    private suspend fun collectTableInputs(
        urlStates: List<BatchUrlState>,
        jobId: Long
    ): List<TableInterpretationBatchInput> {
        val tableInputs = mutableListOf<TableInterpretationBatchInput>()

        urlStates.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                } ?: return@forEach

                // Start with cleaned HTML that has semantic IDs injected
                val cleanedHtml = snapshotData.cleanedHtml ?: snapshotData.html
                val doc = Jsoup.parse(cleanedHtml)

                // Inject media identifiers
                jsoupDomService.injectMediaIdentifiers(doc)

                // Build and apply icon/image replacements
                val mediaReplacements = buildMediaReplacements(snapshotData)
                if (mediaReplacements.isNotEmpty()) {
                    jsoupDomService.replaceElementsWithText(doc, mediaReplacements)
                    logger.debug(
                        "[{}] Applied {} media replacements for {}",
                        jobId, mediaReplacements.size, urlState.url
                    )
                }

                // Get tables for interpretation
                val tables = snapshotData.tableIdentifications ?: emptyList()

                if (tables.isEmpty()) {
                    // Update snapshot with processed HTML (media replaced)
                    val updatedSnapshot = snapshotData.copy(tableMarkdowns = emptyMap())
                    urlState.markFinalLlmDone(json.encodeToString(updatedSnapshot))
                    batchUrlStateRepository.update(urlState)
                    return@forEach
                }

                // Derive bounding boxes for all tables
                val pageBoundingBoxes = snapshotData.boundingBoxes ?: emptyMap()
                val derivedDataMap = boundingBoxDerivationService.deriveElementsBoundingBoxes(
                    cssSelectors = tables.map { it.cssSelector },
                    html = snapshotData.html,
                    pageBoundingBoxes = pageBoundingBoxes
                )

                tables.forEach { table ->
                    val tableElement = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
                    val tableHtml = tableElement?.outerHtml() ?: return@forEach

                    val derivedData = derivedDataMap[table.cssSelector]
                    val tableBoundingBoxes = derivedData?.boundingBoxes ?: emptyMap()

                    tableInputs.add(
                        TableInterpretationBatchInput(
                            urlStateId = urlState.id!!,
                            tableDataId = table.dataId,
                            tableHtml = tableHtml,
                            auxiliaryInfo = table.auxiliaryInfo,
                            boundingBoxes = tableBoundingBoxes
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("[{}] Failed to prepare table batch for {}: {}", jobId, urlState.url, e.message)
            }
        }

        return tableInputs
    }

    private fun buildMediaReplacements(snapshotData: BatchUrlSnapshotData): List<CssSelectorReplacement> {
        val replacements = mutableListOf<CssSelectorReplacement>()

        // Icon replacements
        snapshotData.icons?.forEach { iconData ->
            val label = snapshotData.iconInterpretations?.get(iconData.hashBase64)
            if (label != null) {
                iconData.cssSelectors.forEach { selector ->
                    replacements.add(CssSelectorReplacement(selector, label))
                }
            }
        }

        // Image replacements with XML wrapper
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

    private suspend fun applyCachedResults(
        cachedResults: Map<Pair<Long, String>, String>,
        urlStates: List<BatchUrlState>
    ) {
        val urlTableMarkdowns = mutableMapOf<Long, MutableMap<String, String>>()
        for ((key, markdown) in cachedResults) {
            val (urlStateId, tableDataId) = key
            urlTableMarkdowns.getOrPut(urlStateId) { mutableMapOf() }[tableDataId] = markdown
        }

        for ((urlStateId, tableMarkdowns) in urlTableMarkdowns) {
            val urlState = urlStates.find { it.id == urlStateId } ?: continue
            val snapshotData =
                urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: continue

            val allTables = snapshotData.tableIdentifications ?: emptyList()
            val allTablesCached = allTables.all { tableMarkdowns.containsKey(it.dataId) }

            if (allTablesCached) {
                val updatedSnapshot = snapshotData.copy(tableMarkdowns = tableMarkdowns)
                urlState.markFinalLlmDone(json.encodeToString(updatedSnapshot))
                batchUrlStateRepository.update(urlState)
            }
        }
    }

    private suspend fun markAllUrlsAsDone(urlStates: List<BatchUrlState>) {
        urlStates.forEach { urlState ->
            if (urlState.stage != BatchUrlProcessingStage.FINAL_LLM_DONE) {
                val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) }
                if (snapshotData != null) {
                    urlState.markFinalLlmDone(json.encodeToString(snapshotData.copy(tableMarkdowns = emptyMap())))
                    batchUrlStateRepository.update(urlState)
                }
            }
        }
    }

    private suspend fun processTableBatchResults(jobId: Long, batchJobId: String) {
        logger.info("[{}] Processing table batch results from: {}", jobId, batchJobId)

        val results = geminiBatchService.fetchBatchResults(batchJobId)
        val mappings = tableInterpretationMappings.remove(jobId) ?: emptyList()

        val resultsByUrlState = mutableMapOf<Long, MutableMap<String, String>>()
        val urlsNeedingProcessingForHash = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)

        mappings.forEach { (urlStateId, tableDataId, requestIndex) ->
            if (requestIndex >= results.size) return@forEach

            val result = results[requestIndex]
            if (result.success && result.generatedText != null) {
                val tableMarkdowns = resultsByUrlState.getOrPut(urlStateId) { mutableMapOf() }
                val markdown = tableInterpretationService.parseBatchResponse(result.generatedText!!)
                tableMarkdowns[tableDataId] = markdown

                // Cache the result
                val urlState = urlsNeedingProcessingForHash.find { it.id == urlStateId }
                if (urlState != null) {
                    cacheTableResult(urlState, tableDataId, markdown)
                }
            }
        }

        // Update all URL states with results
        val urlsToUpdate = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)
        urlsToUpdate.forEach { urlState ->
            try {
                val snapshotData = urlState.snapshotData?.let {
                    json.decodeFromString<BatchUrlSnapshotData>(it)
                } ?: return@forEach

                val tableMarkdowns = resultsByUrlState[urlState.id] ?: emptyMap()
                val updatedSnapshot = snapshotData.copy(tableMarkdowns = tableMarkdowns)
                urlState.markFinalLlmDone(json.encodeToString(updatedSnapshot))
                batchUrlStateRepository.update(urlState)

                logger.debug(
                    "[{}] Table interpretation complete for {}: {} tables",
                    jobId, urlState.url, tableMarkdowns.size
                )
            } catch (e: Exception) {
                logger.warn("[{}] Failed to process table results for {}: {}", jobId, urlState.url, e.message)
                urlState.markFailed("Failed to process table results: ${e.message}")
                batchUrlStateRepository.update(urlState)
            }
        }
    }

    private suspend fun cacheTableResult(urlState: BatchUrlState, tableDataId: String, markdown: String) {
        val snapshotData = urlState.snapshotData?.let { json.decodeFromString<BatchUrlSnapshotData>(it) } ?: return
        val table = snapshotData.tableIdentifications?.find { it.dataId == tableDataId } ?: return

        val cleanedHtml = snapshotData.cleanedHtml ?: snapshotData.html
        val doc = Jsoup.parse(cleanedHtml)
        val tableElement = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
        val tableHtml = tableElement?.outerHtml() ?: return

        val tableHash = MessageDigest.getInstance("SHA-256").digest(tableHtml.toByteArray())
        tableInterpretationService.cacheResult(tableHash, markdown)
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

