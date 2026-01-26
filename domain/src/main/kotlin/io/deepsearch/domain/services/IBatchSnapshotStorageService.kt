package io.deepsearch.domain.services

import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.valueobjects.SemanticElements

/**
 * Service for storing batch processing snapshot data in cloud storage.
 * 
 * Replaces the previous approach of storing all intermediate data as JSON in PostgreSQL.
 * Uses a directory-based structure in GCS for efficient per-file access and binary storage.
 * 
 * Directory structure:
 * ```
 * batch-{jobId}/{urlHash}/
 * ├── metadata.json           # title, description
 * ├── html.txt                # Raw HTML
 * ├── bounding-boxes.json     # Element positions
 * ├── screenshot.webp         # Binary screenshot
 * ├── cleaned-html.txt        # Stage 2 output
 * ├── semantic-elements.json  # Stage 2 output
 * ├── table-identifications.json
 * ├── icon-interpretations.json
 * ├── image-texts.json
 * ├── table-markdowns.json
 * ├── kg-extraction.json
 * ├── icons/
 * │   ├── manifest.json       # { hash: { mimeType, selectors } }
 * │   └── {hash}.webp         # Binary icon files
 * └── images/
 *     ├── manifest.json       # { hash: { mimeType, selectors } }
 *     └── {hash}.webp         # Binary image files
 * ```
 * 
 * Benefits over PostgreSQL storage:
 * - No DB bloat from large Base64-encoded images
 * - Binary storage (no +33% Base64 overhead)
 * - Per-file access (read only what you need)
 * - Automatic cleanup via GCS lifecycle policies
 */
interface IBatchSnapshotStorageService {
    
    // ==================== Stage 1: Store Extraction Results ====================
    
    /**
     * Store all extraction results from Stage 1 (browser crawl + extract).
     * 
     * @param jobId Batch job ID
     * @param urlHash SHA-256 hash of the URL (used as directory name)
     * @param extraction Extraction data from browser
     * @return Base path for this URL's snapshot (e.g., "batch-123/abc456")
     */
    suspend fun storeExtraction(
        jobId: Long,
        urlHash: String,
        extraction: ExtractionData
    ): String
    
    // ==================== Stage 2: Read/Write Content LLM Data ====================
    
    /**
     * Read HTML for content LLM processing.
     */
    suspend fun readHtml(basePath: String): String?
    
    /**
     * Read screenshot for vision-based processing.
     */
    suspend fun readScreenshot(basePath: String): ScreenshotData?
    
    /**
     * Read all icons for interpretation.
     * @return List of icon data with hash, bytes, mimeType, and selectors
     */
    suspend fun readIcons(basePath: String): List<MediaFileData>
    
    /**
     * Read all images for classification/extraction.
     * @return List of image data with hash, bytes, mimeType, and selectors
     */
    suspend fun readImages(basePath: String): List<MediaFileData>
    
    /**
     * Store Stage 2 content LLM results.
     */
    suspend fun storeContentLlmResults(
        basePath: String,
        results: ContentLlmResults
    )
    
    /**
     * Delete icons after Stage 2 processing (no longer needed).
     */
    suspend fun deleteIcons(basePath: String)
    
    // ==================== Stage 3: Read/Write Final LLM Data ====================
    
    /**
     * Read bounding boxes for table interpretation.
     */
    suspend fun readBoundingBoxes(basePath: String): Map<String, IBrowserPage.BoundingBox>?
    
    /**
     * Read hidden container bounding boxes for spatial table detection.
     */
    suspend fun readHiddenContainerBoundingBoxes(basePath: String): IBrowserPage.HiddenContainerBoundingBoxes?
    
    /**
     * Read table identifications from Stage 2.
     */
    suspend fun readTableIdentifications(basePath: String): List<TableIdentification>?
    
    /**
     * Store Stage 3 table markdown results.
     */
    suspend fun storeTableMarkdowns(basePath: String, tableMarkdowns: Map<String, String>)
    
    /**
     * Delete images after Stage 3 processing.
     * Images are moved to permanent storage during processing.
     */
    suspend fun deleteImages(basePath: String)
    
    // ==================== Stage 4: Read for Caching ====================
    
    /**
     * Read all data needed for final caching.
     */
    suspend fun readForCaching(basePath: String): CachingData?
    
    /**
     * Store knowledge graph extraction result.
     */
    suspend fun storeKgExtractionResult(basePath: String, result: KgExtractionResult)
    
    /**
     * Read knowledge graph extraction result.
     */
    suspend fun readKgExtractionResult(basePath: String): KgExtractionResult?
    
    // ==================== Cleanup ====================
    
    /**
     * Delete all snapshot data for a URL after successful caching.
     * 
     * @param basePath Base path returned from [storeExtraction]
     * @return Number of files deleted
     */
    suspend fun deleteUrl(basePath: String): Int
    
    /**
     * Delete all snapshot data for an entire batch job.
     * Called when job completes or is cancelled.
     * 
     * @param jobId Batch job ID
     * @return Number of files deleted
     */
    suspend fun deleteJob(jobId: Long): Int
    
    /**
     * Check if snapshot data exists for a URL.
     */
    suspend fun exists(basePath: String): Boolean
}

// ==================== Data Classes ====================

/**
 * Extraction data from Stage 1 browser crawl.
 */
data class ExtractionData(
    val html: String,
    val title: String?,
    val description: String?,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
    val screenshot: ScreenshotData?,
    val icons: List<IconData>,
    val images: List<ImageData>,
    /** Hidden container bounding boxes for spatial table detection */
    val hiddenContainerBoundingBoxes: IBrowserPage.HiddenContainerBoundingBoxes? = null
)

/**
 * Screenshot data.
 */
data class ScreenshotData(
    val bytes: ByteArray,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ScreenshotData
        return bytes.contentEquals(other.bytes) && mimeType == other.mimeType
    }
    
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * Icon data for storage.
 */
data class IconData(
    val hash: String,  // Base64 URL-safe hash
    val bytes: ByteArray,
    val mimeType: String,
    val cssSelectors: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IconData
        return hash == other.hash
    }
    
    override fun hashCode(): Int = hash.hashCode()
}

/**
 * Image data for storage.
 */
data class ImageData(
    val hash: String,  // Base64 URL-safe hash
    val bytes: ByteArray,
    val mimeType: String,
    val cssSelectors: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageData
        return hash == other.hash
    }
    
    override fun hashCode(): Int = hash.hashCode()
}

/**
 * Media file data when reading from storage.
 * Includes the hash as identifier and all associated data.
 */
data class MediaFileData(
    val hash: String,
    val bytes: ByteArray,
    val mimeType: String,
    val cssSelectors: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaFileData
        return hash == other.hash
    }
    
    override fun hashCode(): Int = hash.hashCode()
}

/**
 * Semantic HTML table data extracted via static analysis.
 */
data class SemanticTableData(
    /** Stable element ID (data-ds-id) */
    val dataId: String,
    /** CSS selector using data-ds-id */
    val cssSelector: String,
    /** Outer HTML of the <table> element */
    val tableHtml: String
)

/**
 * Stage 2 content LLM processing results.
 */
data class ContentLlmResults(
    val cleanedHtml: String?,
    val semanticElements: SemanticElements?,
    /** Vision-detected CSS/div-based tables (need LLM interpretation) */
    val tableIdentifications: List<TableIdentification>?,
    /** Semantic HTML tables extracted via static analysis (programmatic conversion + LLM classification) */
    val semanticTableData: List<SemanticTableData>? = null,
    val iconInterpretations: Map<String, String?>?,
    val imageTexts: Map<String, String?>?
)

/**
 * All data needed for Stage 4 caching.
 */
data class CachingData(
    val html: String,
    val cleanedHtml: String?,
    val semanticElements: SemanticElements?,
    /** Vision-detected CSS/div-based tables */
    val tableIdentifications: List<TableIdentification>?,
    /** Semantic HTML tables from static analysis */
    val semanticTableData: List<SemanticTableData>? = null,
    val tableMarkdowns: Map<String, String>?,
    val iconInterpretations: Map<String, String?>?,
    val imageTexts: Map<String, String?>?,
    val boundingBoxes: Map<String, IBrowserPage.BoundingBox>?,
    /** Hidden container bounding boxes for spatial table detection */
    val hiddenContainerBoundingBoxes: IBrowserPage.HiddenContainerBoundingBoxes? = null
)
