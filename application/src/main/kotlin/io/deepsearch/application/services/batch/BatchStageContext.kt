package io.deepsearch.application.services.batch

import io.deepsearch.domain.browser.IBrowserPage
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
    /** Stage 1: HTML URLs that have been crawled + browser extracted */
    val urlsProcessed: Int,
    /** Stage 2: HTML URLs with content LLM processing complete */
    val urlsContentProcessed: Int,
    /** Stage 3: HTML URLs with final LLM processing complete */
    val urlsFinalProcessed: Int,
    /** Stage 4: URLs written to cache (both HTML and FILE) */
    val urlsCached: Int,
    val totalUrls: Int,
    /** File URLs pending upload (discovered, waiting to be uploaded) */
    val filesPendingUpload: Int = 0,
    /** File URLs that have been uploaded (ready for Stage 4 processing) */
    val filesUploaded: Int = 0,
    /** Active Gemini batch job IDs for the current stage (1 for most stages, 2 for stage 4) */
    val batchJobIds: List<String> = emptyList(),
    val estimatedCompletionTime: String? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

// ==================== Content LLM Batch Data Classes ====================

/**
 * Per-URL page data collected from GCS for content LLM processing.
 */
data class UrlPageData(
    val urlStateId: BatchUrlStateId,
    /** GCS base path for this URL's snapshot data */
    val basePath: String,
    val html: String,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
    val iconHashes: List<MediaHash>,
    val imageHashes: List<MediaHash>,
    /** Raw screenshot bytes loaded from GCS */
    val screenshotBytes: ByteArray? = null,
    /** Screenshot MIME type */
    val screenshotMimeType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UrlPageData
        return urlStateId == other.urlStateId && basePath == other.basePath
    }
    
    override fun hashCode(): Int = urlStateId.hashCode()
}

/** Icon data loaded from GCS (used in ContentCollectionResult) */
data class GcsIconData(val bytes: ByteArray, val mimeType: String, val cssSelectors: List<String>)

/** Image data loaded from GCS (used in ContentCollectionResult) */
data class GcsImageData(val bytes: ByteArray, val mimeType: String, val cssSelectors: List<String>)

/**
 * Aggregated collection result with per-URL data and deduplicated media.
 * Icons and images are deduplicated globally by hash.
 * All data is loaded from GCS (not database).
 */
data class ContentCollectionResult(
    val urlPages: Map<BatchUrlStateId, UrlPageData>,
    val uniqueIcons: Map<MediaHash, GcsIconData>,
    val uniqueImages: Map<MediaHash, GcsImageData>
) {
    /**
     * Get the pages map in the format expected by batch services.
     * Includes screenshot data for vision-based identification.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun pagesForBatchServices(): Map<BatchUrlStateId, PageHtmlWithBoundingBoxes> {
        return urlPages.mapValues { (_, data) -> 
            PageHtmlWithBoundingBoxes(
                html = data.html, 
                boundingBoxes = data.boundingBoxes,
                // Convert raw bytes to Base64 for services that expect it
                screenshotBase64 = data.screenshotBytes?.let { kotlin.io.encoding.Base64.encode(it) },
                screenshotMimeType = data.screenshotMimeType
            ) 
        }
    }
    
    fun isEmpty(): Boolean = urlPages.isEmpty() && uniqueIcons.isEmpty() && uniqueImages.isEmpty()
}

/**
 * Holds all submitted batch IDs for parallel batch processing.
 */
data class SubmittedBatches(
    val visualId: String?,
    val iconId: String?,
    val imageClassId: String?
) {
    fun allIds(): List<String> = listOfNotNull(visualId, iconId, imageClassId)
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
    val needsTableInterpretation: Boolean = false
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
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
    /** Base64-encoded full-page screenshot for vision-based identification */
    val screenshotBase64: String? = null,
    /** MIME type for screenshot (e.g., "image/png") */
    val screenshotMimeType: String? = null
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
)
