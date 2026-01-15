package io.deepsearch.application.services.batch

import io.deepsearch.application.services.IVisualIdentificationService
import io.deepsearch.application.services.IWebpageIconInterpretationService
import io.deepsearch.application.services.IWebpageImageTextExtractionService
import io.deepsearch.application.services.ImageBatchPreparation
import io.deepsearch.application.services.IconBatchPreparation
import io.deepsearch.application.services.VisualIdentificationBatchPreparation
import io.deepsearch.domain.models.valueobjects.MediaHash
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Phase 2 of ContentLlmBatchHandler: Prepare batch requests.
 * Prepares visual identification, icon interpretation, and image classification batches.
 */
class ContentBatchPreparer(
    private val visualIdentificationService: IVisualIdentificationService,
    private val webpageIconInterpretationService: IWebpageIconInterpretationService,
    private val webpageImageTextExtractionService: IWebpageImageTextExtractionService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Holds all batch preparations with their data maps.
     */
    data class BatchPreparations(
        val visualPrep: VisualIdentificationBatchPreparation,
        val iconPrep: IconBatchPreparation,
        val imagePrep: ImageBatchPreparation,
        val iconDataMap: Map<MediaHash, MediaData>,
        val imageDataMap: Map<MediaHash, MediaData>
    )

    /**
     * Prepare all batch requests for content LLM processing.
     *
     * @param collected Content collection result from Phase 1
     * @param jobId Batch job ID for logging
     * @return Batch preparations with cached results and pending requests
     */
    suspend fun prepare(collected: ContentCollectionResult, jobId: Long): BatchPreparations {
        val pagesForServices = collected.pagesForBatchServices()

        // Prepare combined visual identification batch (semantic + tables in single call)
        val visualPrep = visualIdentificationService.prepareBatchRequests(pagesForServices, jobId)

        // Prepare icon interpretation batch (bytes already loaded from GCS)
        val iconDataMap = collected.uniqueIcons.mapValues { (_, iconData) ->
            MediaData(iconData.bytes, iconData.mimeType)
        }
        val iconPrep = webpageIconInterpretationService.prepareBatchRequests(iconDataMap, jobId)

        // Prepare image classification batch (bytes already loaded from GCS)
        val imageDataMap = collected.uniqueImages.mapValues { (_, imageData) ->
            MediaData(imageData.bytes, imageData.mimeType)
        }
        val imagePrep = webpageImageTextExtractionService.prepareBatchRequests(imageDataMap, jobId)

        logger.info(
            "[{}] Prepared batches: {} visual (semantic+tables), {} icons, {} images",
            jobId, visualPrep.pendingRequests.size,
            iconPrep.pendingRequests.size, imagePrep.pendingRequests.size
        )

        return BatchPreparations(visualPrep, iconPrep, imagePrep, iconDataMap, imageDataMap)
    }
}
