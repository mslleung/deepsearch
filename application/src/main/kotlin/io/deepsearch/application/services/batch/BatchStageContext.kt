package io.deepsearch.application.services.batch

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.BatchIconData
import io.deepsearch.domain.models.entities.BatchImageData
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.models.valueobjects.TableDataId

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
    /** Active Gemini batch job IDs for the current stage (1 for most stages, 2 for stage 4) */
    val batchJobIds: List<String> = emptyList(),
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
    val urlStateId: BatchUrlStateId,
    val html: String,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
    val iconHashes: List<MediaHash>,
    val imageHashes: List<MediaHash>
)

/**
 * Aggregated collection result with per-URL data and deduplicated media.
 * Icons and images are deduplicated globally by hash.
 */
data class ContentCollectionResult(
    val urlPages: Map<BatchUrlStateId, UrlPageData>,
    val uniqueIcons: Map<MediaHash, BatchIconData>,
    val uniqueImages: Map<MediaHash, BatchImageData>
) {
    /**
     * Get the pages map in the format expected by batch services.
     */
    fun pagesForBatchServices(): Map<BatchUrlStateId, PageHtmlWithBoundingBoxes> {
        return urlPages.mapValues { (_, data) -> 
            PageHtmlWithBoundingBoxes(data.html, data.boundingBoxes) 
        }
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
    val iconInterpretations: Map<MediaHash, String?>,
    val imageTexts: Map<MediaHash, String?>
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

// ==================== Typed Data Classes ====================

/**
 * Encapsulates media (icon/image) binary data with its MIME type.
 * Replaces Pair<ByteArray, String> for better readability.
 */
data class MediaData(
    val bytes: ByteArray,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaData
        return bytes.contentEquals(other.bytes) && mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * Encapsulates page HTML with its bounding boxes for batch processing.
 * Replaces Pair<String, Map<String, BoundingBox>> for better readability.
 */
data class PageHtmlWithBoundingBoxes(
    val html: String,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>
)

/**
 * Composite key identifying a specific table within a URL state.
 * Replaces Pair<Long, String> for better type safety.
 */
data class TableKey(
    val urlStateId: BatchUrlStateId,
    val tableDataId: TableDataId
)

/**
 * Maps a table to its position in a batch request.
 * Replaces Triple<Long, String, Int> for better readability.
 */
data class TableRequestMapping(
    val urlStateId: BatchUrlStateId,
    val tableDataId: TableDataId,
    val requestIndex: Int
) {
    fun toKey(): TableKey = TableKey(urlStateId, tableDataId)
}


