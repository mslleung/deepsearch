package io.deepsearch.application.services.batch

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.BatchIconData
import io.deepsearch.domain.models.entities.BatchImageData
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState

/**
 * Event emitted during batch periodic index processing.
 * Provides stage-based progress tracking for the frontend.
 */
@kotlinx.serialization.Serializable
data class BatchPeriodicIndexEvent(
    val jobId: Long,
    val baseUrl: String,
    val state: BatchPeriodicIndexJobState,
    val stage: Int,
    val stageDescription: String,
    /** Stage 1: URLs that have been crawled + browser extracted */
    val urlsProcessed: Int,
    /** Stage 2: URLs with content LLM processing complete */
    val urlsContentProcessed: Int,
    /** Stage 3: URLs with final LLM processing complete */
    val urlsFinalProcessed: Int,
    /** Stage 4: URLs written to cache */
    val urlsCached: Int,
    val totalUrls: Int,
    val geminiBatchJobId: String? = null,
    val estimatedCompletionTime: String? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

// ==================== Content LLM Batch Data Classes ====================

/**
 * Per-URL page data collected from snapshots for content LLM processing.
 */
data class UrlPageData(
    val urlStateId: Long,
    val html: String,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
    val iconHashes: List<String>,
    val imageHashes: List<String>
)

/**
 * Aggregated collection result with per-URL data and deduplicated media.
 * Icons and images are deduplicated globally by hash.
 */
data class ContentCollectionResult(
    val urlPages: Map<Long, UrlPageData>,
    val uniqueIcons: Map<String, BatchIconData>,
    val uniqueImages: Map<String, BatchImageData>
) {
    /**
     * Get the pages map in the format expected by batch services.
     */
    fun pagesForBatchServices(): Map<Long, Pair<String, Map<String, IBrowserPage.BoundingBox>>> {
        return urlPages.mapValues { (_, data) -> data.html to data.boundingBoxes }
    }
    
    fun isEmpty(): Boolean = urlPages.isEmpty() && uniqueIcons.isEmpty() && uniqueImages.isEmpty()
}

/**
 * Holds all submitted batch IDs for parallel batch processing.
 */
data class SubmittedBatches(
    val semanticId: String?,
    val tableId: String?,
    val iconId: String?,
    val imageClassId: String?
) {
    fun allIds(): List<String> = listOfNotNull(semanticId, tableId, iconId, imageClassId)
    fun count(): Int = allIds().size
}

/**
 * Processed media results from batch processing.
 * Maps hash -> interpretation/text.
 */
data class MediaResults(
    val iconInterpretations: Map<String, String?>,
    val imageTexts: Map<String, String?>
)

// ==================== JSON Response Wrappers ====================

/**
 * JSON response wrapper for icon batch results.
 */
@kotlinx.serialization.Serializable
data class IconBatchResponseWrapper(
    val icons: List<IconLabelWrapper> = emptyList()
)

@kotlinx.serialization.Serializable
data class IconLabelWrapper(
    val label: String? = null
)

/**
 * JSON response wrapper for image classification batch results.
 */
@kotlinx.serialization.Serializable
data class ImageClassificationResponseWrapper(
    val imageType: String = "ILLUSTRATIVE",
    val text: String? = null,
    val containsTable: Boolean = false
)

/**
 * JSON response wrapper for table extraction batch results.
 */
@kotlinx.serialization.Serializable
data class TableExtractionResponseWrapper(
    val text: String? = null
)


