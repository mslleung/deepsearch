package io.deepsearch.application.services.batch

import io.deepsearch.application.services.ITableInterpretationService
import io.deepsearch.application.services.TableInterpretationBatchInput
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchJobId
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.TableDataId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.domain.services.*
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val snapshotStorage: IBatchSnapshotStorageService,
    private val eventEmitter: BatchEventEmitter,
    private val batchTokenUsageRecorder: BatchTokenUsageRecorder,
    private val pollingService: BatchPollingService
) : IBatchStageHandler {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // Mapping storage for batch result processing
    private val tableInterpretationMappings = ConcurrentHashMap<BatchJobId, List<TableRequestMapping>>()

    override suspend fun execute(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>
    ) {
        val jobId = requireNotNull(job.id)
        logger.info("[{}] Stage 3: LLM table interpretation", jobId)
        eventEmitter.emit(job, eventFlow, "Stage 3: Applying media replacements and preparing table interpretation...")

        val batchJobIdTyped = BatchJobId(jobId)

        // Check if we already have a batch job running (resume case)
        if (job.batchJobIds.isNotEmpty()) {
            handleResume(job, eventFlow, jobId, batchJobIdTyped)
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
        val pendingRequests = tableBatchPrep.pendingRequests
        val cachedTableResults = tableBatchPrep.cachedResults

        // Apply cached results immediately
        applyCachedResults(cachedTableResults, urlsNeedingProcessing)

        if (pendingRequests.isEmpty()) {
            markAllUrlsAsDone(urlsNeedingProcessing)
            job.urlsFinalProcessed = urlsNeedingProcessing.size
            job.advanceToNextStage()
            batchJobRepository.update(job)
            eventEmitter.emit(job, eventFlow, "Stage 3 complete: All tables cached or no tables to interpret")
            return
        }

        // Store mapping for result processing
        val requestMappings = pendingRequests.mapIndexed { index, pending ->
            TableRequestMapping(pending.key.urlStateId, pending.key.tableDataId, index)
        }
        tableInterpretationMappings[batchJobIdTyped] = requestMappings

        logger.info(
            "[{}] Submitting {} table interpretation requests ({} cached)",
            jobId, pendingRequests.size, cachedTableResults.size
        )

        try {
            val batchJobId = geminiBatchService.createContentBatch(pendingRequests.map { it.request })
            job.addBatchJob(batchJobId)
            batchJobRepository.update(job)

            logger.info("[{}] Submitted table batch job: {}", jobId, batchJobId)
            eventEmitter.emit(job, eventFlow, "Table batch submitted: $batchJobId (${pendingRequests.size} tables)")

            pollingService.pollUntilComplete(job, eventFlow, batchJobId, "table interpretation")
            processTableBatchResults(jobId, batchJobId)

            job.clearBatchJobs()
            job.advanceToNextStage()
            batchJobRepository.update(job)
        } catch (e: Exception) {
            logger.error("[{}] Failed to submit table batch: {}", jobId, e.message, e)
            throw e
        }
    }

    private suspend fun handleResume(
        job: BatchPeriodicIndexJob,
        eventFlow: MutableSharedFlow<BatchPeriodicIndexEvent>,
        jobId: Long,
        batchJobIdTyped: BatchJobId
    ) {
        // Rebuild mapping from database state for resumability
        val urlsNeedingProcessing = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)
        val tableInputs = collectTableInputs(urlsNeedingProcessing, jobId)
        val tableBatchPrep = tableInterpretationService.prepareBatchRequests(tableInputs, jobId)
        val requestMappings = tableBatchPrep.pendingRequests.mapIndexed { index, pending ->
            TableRequestMapping(pending.key.urlStateId, pending.key.tableDataId, index)
        }
        tableInterpretationMappings[batchJobIdTyped] = requestMappings
        logger.info("[{}] Rebuilt table interpretation mapping with {} entries for resume", jobId, requestMappings.size)

        val batchJobId = job.batchJobIds.first()
        pollingService.pollUntilComplete(job, eventFlow, batchJobId, "table interpretation")
        processTableBatchResults(jobId, batchJobId)
        job.clearBatchJobs()
        job.advanceToNextStage()
        batchJobRepository.update(job)
    }

    /**
     * Collected data for a URL from GCS.
     */
    private data class UrlSnapshotFromGcs(
        val basePath: String,
        val html: String,
        val cleanedHtml: String?,
        val boundingBoxes: Map<String, io.deepsearch.domain.browser.IBrowserPage.BoundingBox>,
        val tableIdentifications: List<io.deepsearch.domain.agents.TableIdentification>,
        val iconInterpretations: Map<String, String?>?,
        val imageTexts: Map<String, String?>?,
        val icons: List<MediaFileData>,
        val images: List<MediaFileData>
    )

    private suspend fun collectTableInputs(
        urlStates: List<BatchUrlState>,
        jobId: Long
    ): List<TableInterpretationBatchInput> {
        val tableInputs = mutableListOf<TableInterpretationBatchInput>()

        urlStates.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach

                // Read data from GCS
                val html = snapshotStorage.readHtml(basePath) ?: return@forEach
                val cachingData = snapshotStorage.readForCaching(basePath) ?: return@forEach
                val icons = snapshotStorage.readIcons(basePath)
                val images = snapshotStorage.readImages(basePath)

                val snapshotFromGcs = UrlSnapshotFromGcs(
                    basePath = basePath,
                    html = html,
                    cleanedHtml = cachingData.cleanedHtml,
                    boundingBoxes = cachingData.boundingBoxes ?: emptyMap(),
                    tableIdentifications = cachingData.tableIdentifications ?: emptyList(),
                    iconInterpretations = cachingData.iconInterpretations,
                    imageTexts = cachingData.imageTexts,
                    icons = icons,
                    images = images
                )

                // Start with cleaned HTML that has semantic IDs injected
                val cleanedHtml = snapshotFromGcs.cleanedHtml ?: snapshotFromGcs.html
                val doc = Jsoup.parse(cleanedHtml)

                // Inject media identifiers
                jsoupDomService.injectMediaIdentifiers(doc)

                // Build and apply media replacements using shared utility
                val mediaResult = MediaReplacementBuilder.buildFromIconsAndImages(
                    snapshotFromGcs.icons,
                    snapshotFromGcs.images,
                    snapshotFromGcs.iconInterpretations,
                    snapshotFromGcs.imageTexts
                )
                if (mediaResult.replacements.isNotEmpty()) {
                    jsoupDomService.replaceElementsWithText(doc, mediaResult.replacements)
                    logger.debug(
                        "[{}] Applied {} media replacements for {}",
                        jobId, mediaResult.replacements.size, urlState.url
                    )
                }

                // Get tables for interpretation
                val tables = snapshotFromGcs.tableIdentifications

                if (tables.isEmpty()) {
                    // No tables - store empty table markdowns and mark done
                    snapshotStorage.storeTableMarkdowns(basePath, emptyMap())
                    urlState.markFinalLlmDone()
                    batchUrlStateRepository.update(urlState)
                    return@forEach
                }

                // Derive bounding boxes for all tables
                val pageBoundingBoxes = snapshotFromGcs.boundingBoxes
                val derivedDataMap = boundingBoxDerivationService.deriveElementsBoundingBoxes(
                    cssSelectors = tables.map { it.cssSelector },
                    html = snapshotFromGcs.html,
                    pageBoundingBoxes = pageBoundingBoxes
                )

                tables.forEach { table ->
                    val tableElement = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
                    val tableHtml = tableElement?.outerHtml() ?: return@forEach

                    val derivedData = derivedDataMap[table.cssSelector]
                    val tableBoundingBoxes = derivedData?.boundingBoxes ?: emptyMap()

                    tableInputs.add(
                        TableInterpretationBatchInput(
                            urlStateId = BatchUrlStateId(urlState.id!!),
                            tableDataId = TableDataId(table.dataId),
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

    private suspend fun applyCachedResults(
        cachedResults: Map<TableKey, String>,
        urlStates: List<BatchUrlState>
    ) {
        val urlTableMarkdowns = mutableMapOf<BatchUrlStateId, MutableMap<String, String>>()
        for ((key, markdown) in cachedResults) {
            urlTableMarkdowns.getOrPut(key.urlStateId) { mutableMapOf() }[key.tableDataId.value] = markdown
        }

        for ((urlStateId, tableMarkdowns) in urlTableMarkdowns) {
            val urlState = urlStates.find { it.id == urlStateId.value } ?: continue
            val basePath = urlState.snapshotBasePath ?: continue

            // Read table identifications from GCS
            val tables = snapshotStorage.readTableIdentifications(basePath) ?: emptyList()
            val allTablesCached = tables.all { tableMarkdowns.containsKey(it.dataId) }

            if (allTablesCached) {
                // Store table markdowns to GCS
                snapshotStorage.storeTableMarkdowns(basePath, tableMarkdowns)
                urlState.markFinalLlmDone()
                batchUrlStateRepository.update(urlState)
            }
        }
    }

    private suspend fun markAllUrlsAsDone(urlStates: List<BatchUrlState>) {
        urlStates.forEach { urlState ->
            if (urlState.stage != BatchUrlProcessingStage.FINAL_LLM_DONE) {
                val basePath = urlState.snapshotBasePath
                if (basePath != null) {
                    // Store empty table markdowns to GCS
                    snapshotStorage.storeTableMarkdowns(basePath, emptyMap())

                    // Delete images from GCS (no longer needed after Stage 3)
                    try {
                        snapshotStorage.deleteImages(basePath)
                    } catch (e: Exception) {
                        logger.warn("Failed to delete images for {}: {}", urlState.url, e.message)
                    }

                    urlState.markFinalLlmDone()
                    batchUrlStateRepository.update(urlState)
                }
            }
        }
    }

    private suspend fun processTableBatchResults(jobId: Long, batchJobId: String) {
        logger.info("[{}] Processing table batch results from: {}", jobId, batchJobId)

        val results = geminiBatchService.fetchBatchResults(batchJobId)

        // Record token usage for table interpretation batch
        val modelId = "gemini-2.5-flash-lite-preview-09-2025" // Table interpretation uses Flash Lite
        batchTokenUsageRecorder.recordBatchTokenUsage(jobId, "TableInterpretationBatch", modelId, results)

        val batchJobIdTyped = BatchJobId(jobId)
        val mappings = tableInterpretationMappings.remove(batchJobIdTyped) ?: emptyList()

        val resultsByUrlState = mutableMapOf<BatchUrlStateId, MutableMap<String, String>>()
        val urlsNeedingProcessingForHash = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)

        mappings.forEach { mapping ->
            if (mapping.requestIndex >= results.size) return@forEach

            val result = results[mapping.requestIndex]
            if (result.success && result.generatedText != null) {
                val tableMarkdowns = resultsByUrlState.getOrPut(mapping.urlStateId) { mutableMapOf() }
                val markdown = tableInterpretationService.parseBatchResponse(result.generatedText!!)
                tableMarkdowns[mapping.tableDataId.value] = markdown

                // Cache the result
                val urlState = urlsNeedingProcessingForHash.find { it.id == mapping.urlStateId.value }
                if (urlState != null) {
                    cacheTableResult(urlState, mapping.tableDataId, markdown)
                }
            }
        }

        // Update all URL states with results
        val urlsToUpdate = batchUrlStateRepository.findNeedingFinalLlmProcessing(jobId)
        urlsToUpdate.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach

                val urlStateId = BatchUrlStateId(urlState.id!!)
                val tableMarkdowns = resultsByUrlState[urlStateId] ?: emptyMap()

                // Store table markdowns to GCS
                snapshotStorage.storeTableMarkdowns(basePath, tableMarkdowns)

                // Delete images from GCS (no longer needed after Stage 3)
                try {
                    snapshotStorage.deleteImages(basePath)
                } catch (e: Exception) {
                    logger.warn("Failed to delete images for {}: {}", urlState.url, e.message)
                }

                urlState.markFinalLlmDone()
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

    private suspend fun cacheTableResult(urlState: BatchUrlState, tableDataId: TableDataId, markdown: String) {
        val basePath = urlState.snapshotBasePath ?: return
        val tables = snapshotStorage.readTableIdentifications(basePath) ?: return
        val table = tables.find { it.dataId == tableDataId.value } ?: return

        val cachingData = snapshotStorage.readForCaching(basePath) ?: return
        val cleanedHtml = cachingData.cleanedHtml ?: cachingData.html
        val doc = Jsoup.parse(cleanedHtml)
        val tableElement = doc.select("[data-ds-id=\"${table.dataId}\"]").firstOrNull()
        val tableHtml = tableElement?.outerHtml() ?: return

        val tableHash = MessageDigest.getInstance("SHA-256").digest(tableHtml.toByteArray())
        tableInterpretationService.cacheResult(tableHash, markdown)
    }
}
