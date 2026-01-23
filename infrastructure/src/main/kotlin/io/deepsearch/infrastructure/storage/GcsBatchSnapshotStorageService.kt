package io.deepsearch.infrastructure.storage

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.GcsConfig
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.services.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Google Cloud Storage implementation of batch snapshot storage.
 * 
 * Uses a directory-based structure for efficient per-file access:
 * ```
 * batch-snapshots/{jobId}/{urlHash}/
 * ├── metadata.json
 * ├── html.txt
 * ├── bounding-boxes.json
 * ├── screenshot.webp
 * ├── cleaned-html.txt
 * ├── semantic-elements.json
 * ├── table-identifications.json
 * ├── icon-interpretations.json
 * ├── image-texts.json
 * ├── table-markdowns.json
 * ├── kg-extraction.json
 * ├── icons/
 * │   ├── manifest.json
 * │   └── {hash}.webp
 * └── images/
 *     ├── manifest.json
 *     └── {hash}.webp
 * ```
 * 
 * @param gcsConfig GCS configuration containing bucket names
 * @param dispatchers Dispatcher provider for IO operations
 */
class GcsBatchSnapshotStorageService(
    gcsConfig: GcsConfig,
    private val dispatchers: IDispatcherProvider
) : IBatchSnapshotStorageService {
    
    private val bucketName = gcsConfig.tempBucketName
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    private val storage: Storage by lazy {
        StorageOptions.getDefaultInstance().service
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    
    companion object {
        private const val PREFIX = "batch-snapshots"
        
        // File names within each URL directory
        private const val METADATA_FILE = "metadata.json"
        private const val HTML_FILE = "html.txt"
        private const val BOUNDING_BOXES_FILE = "bounding-boxes.json"
        private const val HIDDEN_CONTAINER_BBOXES_FILE = "hidden-container-bboxes.json"
        private const val SCREENSHOT_FILE = "screenshot"  // Extension added based on mime type
        private const val CLEANED_HTML_FILE = "cleaned-html.txt"
        private const val SEMANTIC_ELEMENTS_FILE = "semantic-elements.json"
        private const val TABLE_IDENTIFICATIONS_FILE = "table-identifications.json"
        private const val HIDDEN_MOBILE_LAYOUTS_FILE = "hidden-mobile-layouts.json"
        private const val ICON_INTERPRETATIONS_FILE = "icon-interpretations.json"
        private const val IMAGE_TEXTS_FILE = "image-texts.json"
        private const val TABLE_MARKDOWNS_FILE = "table-markdowns.json"
        private const val KG_EXTRACTION_FILE = "kg-extraction.json"
        
        // Subdirectories
        private const val ICONS_DIR = "icons"
        private const val IMAGES_DIR = "images"
        private const val MANIFEST_FILE = "manifest.json"
    }
    
    // ==================== Stage 1: Store Extraction Results ====================
    
    override suspend fun storeExtraction(
        jobId: Long,
        urlHash: String,
        extraction: ExtractionData
    ): String = coroutineScope {
        val basePath = "$PREFIX/$jobId/$urlHash"
        logger.debug("Storing extraction data at gs://{}/{}", bucketName, basePath)
        
        val uploads = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        
        // Store metadata
        uploads.add(async {
            val metadata = SnapshotMetadata(extraction.title, extraction.description)
            storeJson("$basePath/$METADATA_FILE", metadata)
        })
        
        // Store HTML
        uploads.add(async {
            storeText("$basePath/$HTML_FILE", extraction.html)
        })
        
        // Store bounding boxes
        if (extraction.boundingBoxes.isNotEmpty()) {
            uploads.add(async {
                storeJson("$basePath/$BOUNDING_BOXES_FILE", extraction.boundingBoxes.mapValues { 
                    SerializableBoundingBox(it.value.left, it.value.top, it.value.right, it.value.bottom)
                })
            })
        }
        
        // Store screenshot
        extraction.screenshot?.let { screenshot ->
            uploads.add(async {
                val ext = mimeTypeToExtension(screenshot.mimeType)
                storeBinary("$basePath/$SCREENSHOT_FILE.$ext", screenshot.bytes, screenshot.mimeType)
            })
        }
        
        // Store icons
        if (extraction.icons.isNotEmpty()) {
            uploads.add(async {
                storeMediaFiles("$basePath/$ICONS_DIR", extraction.icons.map { 
                    MediaFile(it.hash, it.bytes, it.mimeType, it.cssSelectors)
                })
            })
        }
        
        // Store images
        if (extraction.images.isNotEmpty()) {
            uploads.add(async {
                storeMediaFiles("$basePath/$IMAGES_DIR", extraction.images.map {
                    MediaFile(it.hash, it.bytes, it.mimeType, it.cssSelectors)
                })
            })
        }
        
        // Store hidden container bounding boxes for spatial table detection
        extraction.hiddenContainerBoundingBoxes?.let { hiddenBboxes ->
            if (hiddenBboxes.hiddenContainers.isNotEmpty()) {
                uploads.add(async {
                    storeJson("$basePath/$HIDDEN_CONTAINER_BBOXES_FILE", hiddenBboxes.toSerializable())
                })
            }
        }
        
        uploads.awaitAll()
        logger.info("Stored extraction data: {} ({} icons, {} images, {} hidden containers)", 
            basePath, extraction.icons.size, extraction.images.size, 
            extraction.hiddenContainerBoundingBoxes?.hiddenContainerCount ?: 0)
        
        basePath
    }
    
    // ==================== Stage 2: Read/Write Content LLM Data ====================
    
    override suspend fun readHtml(basePath: String): String? = withContext(dispatchers.io) {
        readText("$basePath/$HTML_FILE")
    }
    
    override suspend fun readScreenshot(basePath: String): ScreenshotData? = withContext(dispatchers.io) {
        // Try common image extensions
        for (ext in listOf("webp", "png", "jpg", "jpeg")) {
            val path = "$basePath/$SCREENSHOT_FILE.$ext"
            val bytes = readBinary(path)
            if (bytes != null) {
                return@withContext ScreenshotData(bytes, extensionToMimeType(ext))
            }
        }
        null
    }
    
    override suspend fun readIcons(basePath: String): List<MediaFileData> = withContext(dispatchers.io) {
        readMediaFiles("$basePath/$ICONS_DIR")
    }
    
    override suspend fun readImages(basePath: String): List<MediaFileData> = withContext(dispatchers.io) {
        readMediaFiles("$basePath/$IMAGES_DIR")
    }
    
    override suspend fun storeContentLlmResults(
        basePath: String,
        results: ContentLlmResults
    ): Unit = coroutineScope {
        val uploads = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        
        results.cleanedHtml?.let { html ->
            uploads.add(async { storeText("$basePath/$CLEANED_HTML_FILE", html) })
        }
        
        results.semanticElements?.let { elements ->
            uploads.add(async { storeJson("$basePath/$SEMANTIC_ELEMENTS_FILE", elements) })
        }
        
        results.tableIdentifications?.let { tables ->
            uploads.add(async { storeJson("$basePath/$TABLE_IDENTIFICATIONS_FILE", tables) })
        }
        
        results.iconInterpretations?.let { interpretations ->
            uploads.add(async { storeJson("$basePath/$ICON_INTERPRETATIONS_FILE", interpretations) })
        }
        
        results.imageTexts?.let { texts ->
            uploads.add(async { storeJson("$basePath/$IMAGE_TEXTS_FILE", texts) })
        }
        
        uploads.awaitAll()
        logger.debug("Stored content LLM results at {}", basePath)
    }
    
    override suspend fun deleteIcons(basePath: String): Unit = withContext(dispatchers.io) {
        deleteDirectory("$basePath/$ICONS_DIR")
    }
    
    // ==================== Stage 3: Read/Write Final LLM Data ====================
    
    override suspend fun readBoundingBoxes(basePath: String): Map<String, IBrowserPage.BoundingBox>? = 
        withContext(dispatchers.io) {
            val data = readJson<Map<String, SerializableBoundingBox>>("$basePath/$BOUNDING_BOXES_FILE")
            data?.mapValues { IBrowserPage.BoundingBox(it.value.left, it.value.top, it.value.right, it.value.bottom) }
        }
    
    override suspend fun readHiddenContainerBoundingBoxes(basePath: String): IBrowserPage.HiddenContainerBoundingBoxes? =
        withContext(dispatchers.io) {
            val data = readJson<SerializableHiddenContainerBoundingBoxes>("$basePath/$HIDDEN_CONTAINER_BBOXES_FILE")
            data?.toDomain()
        }
    
    override suspend fun readTableIdentifications(basePath: String): List<TableIdentification>? =
        withContext(dispatchers.io) {
            readJson("$basePath/$TABLE_IDENTIFICATIONS_FILE")
        }
    
    override suspend fun storeTableMarkdowns(basePath: String, tableMarkdowns: Map<String, String>): Unit =
        withContext(dispatchers.io) {
            storeJson("$basePath/$TABLE_MARKDOWNS_FILE", tableMarkdowns)
        }
    
    override suspend fun deleteImages(basePath: String): Unit = withContext(dispatchers.io) {
        deleteDirectory("$basePath/$IMAGES_DIR")
    }
    
    // ==================== Stage 4: Read for Caching ====================
    
    override suspend fun readForCaching(basePath: String): CachingData? = coroutineScope {
        val html = async { readHtml(basePath) }
        val cleanedHtml = async { readText("$basePath/$CLEANED_HTML_FILE") }
        val semanticElements = async { readJson<SemanticElements>("$basePath/$SEMANTIC_ELEMENTS_FILE") }
        val tableIdentifications = async { readJson<List<TableIdentification>>("$basePath/$TABLE_IDENTIFICATIONS_FILE") }
        val tableMarkdowns = async { readJson<Map<String, String>>("$basePath/$TABLE_MARKDOWNS_FILE") }
        val iconInterpretations = async { readJson<Map<String, String?>>("$basePath/$ICON_INTERPRETATIONS_FILE") }
        val imageTexts = async { readJson<Map<String, String?>>("$basePath/$IMAGE_TEXTS_FILE") }
        val boundingBoxes = async { readBoundingBoxes(basePath) }
        val hiddenBboxes = async { readHiddenContainerBoundingBoxes(basePath) }
        
        val htmlContent = html.await() ?: return@coroutineScope null
        
        CachingData(
            html = htmlContent,
            cleanedHtml = cleanedHtml.await(),
            semanticElements = semanticElements.await(),
            tableIdentifications = tableIdentifications.await(),
            tableMarkdowns = tableMarkdowns.await(),
            iconInterpretations = iconInterpretations.await(),
            imageTexts = imageTexts.await(),
            boundingBoxes = boundingBoxes.await(),
            hiddenContainerBoundingBoxes = hiddenBboxes.await()
        )
    }
    
    override suspend fun storeKgExtractionResult(basePath: String, result: KgExtractionResult): Unit =
        withContext(dispatchers.io) {
            storeJson("$basePath/$KG_EXTRACTION_FILE", result)
        }
    
    override suspend fun readKgExtractionResult(basePath: String): KgExtractionResult? =
        withContext(dispatchers.io) {
            readJson("$basePath/$KG_EXTRACTION_FILE")
        }
    
    // ==================== Cleanup ====================
    
    override suspend fun deleteUrl(basePath: String): Int = withContext(dispatchers.io) {
        deleteDirectory(basePath)
    }
    
    override suspend fun deleteJob(jobId: Long): Int = withContext(dispatchers.io) {
        val prefix = "$PREFIX/$jobId/"
        deleteDirectory(prefix.trimEnd('/'))
    }
    
    override suspend fun exists(basePath: String): Boolean = withContext(dispatchers.io) {
        // Check if metadata file exists as indicator
        val blobId = BlobId.of(bucketName, "$basePath/$METADATA_FILE")
        try {
            storage.get(blobId) != null
        } catch (e: Exception) {
            false
        }
    }
    
    // ==================== Private Helper Methods ====================
    
    private suspend fun storeText(path: String, content: String) = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, path)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType("text/plain; charset=utf-8")
            .build()
        
        try {
            storage.create(blobInfo, content.toByteArray(Charsets.UTF_8))
            logger.debug("Stored text: gs://{}/{}", bucketName, path)
        } catch (e: Exception) {
            logger.error("Failed to store text gs://{}/{}: {}", bucketName, path, e.message)
            throw e
        }
    }
    
    private suspend fun readText(path: String): String? = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, path)
        
        try {
            val blob = storage.get(blobId) ?: return@withContext null
            String(blob.getContent(), Charsets.UTF_8)
        } catch (e: Exception) {
            logger.debug("Failed to read text gs://{}/{}: {}", bucketName, path, e.message)
            null
        }
    }
    
    private suspend inline fun <reified T> storeJson(path: String, data: T) = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, path)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType("application/json")
            .build()
        
        try {
            val jsonStr = json.encodeToString(data)
            storage.create(blobInfo, jsonStr.toByteArray(Charsets.UTF_8))
            logger.debug("Stored JSON: gs://{}/{}", bucketName, path)
        } catch (e: Exception) {
            logger.error("Failed to store JSON gs://{}/{}: {}", bucketName, path, e.message)
            throw e
        }
    }
    
    private suspend inline fun <reified T> readJson(path: String): T? = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, path)
        
        try {
            val blob = storage.get(blobId) ?: return@withContext null
            val jsonStr = String(blob.getContent(), Charsets.UTF_8)
            json.decodeFromString<T>(jsonStr)
        } catch (e: Exception) {
            logger.debug("Failed to read JSON gs://{}/{}: {}", bucketName, path, e.message)
            null
        }
    }
    
    private suspend fun storeBinary(path: String, bytes: ByteArray, mimeType: String) = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, path)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(mimeType)
            .build()
        
        try {
            storage.create(blobInfo, bytes)
            logger.debug("Stored binary: gs://{}/{} ({} bytes)", bucketName, path, bytes.size)
        } catch (e: Exception) {
            logger.error("Failed to store binary gs://{}/{}: {}", bucketName, path, e.message)
            throw e
        }
    }
    
    private suspend fun readBinary(path: String): ByteArray? = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, path)
        
        try {
            val blob = storage.get(blobId) ?: return@withContext null
            blob.getContent()
        } catch (e: Exception) {
            logger.debug("Failed to read binary gs://{}/{}: {}", bucketName, path, e.message)
            null
        }
    }
    
    private data class MediaFile(
        val hash: String,
        val bytes: ByteArray,
        val mimeType: String,
        val cssSelectors: List<String>
    )
    
    private suspend fun storeMediaFiles(dirPath: String, files: List<MediaFile>) = coroutineScope {
        if (files.isEmpty()) return@coroutineScope
        
        // Store manifest
        val manifest = files.associate { file ->
            file.hash to MediaManifestEntry(file.mimeType, file.cssSelectors)
        }
        
        val uploads = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        
        uploads.add(async {
            storeJson("$dirPath/$MANIFEST_FILE", manifest)
        })
        
        // Store each file
        for (file in files) {
            uploads.add(async {
                val ext = mimeTypeToExtension(file.mimeType)
                val safeHash = file.hash.replace("+", "-").replace("/", "_").trimEnd('=')
                storeBinary("$dirPath/$safeHash.$ext", file.bytes, file.mimeType)
            })
        }
        
        uploads.awaitAll()
        logger.debug("Stored {} media files at {}", files.size, dirPath)
    }
    
    private suspend fun readMediaFiles(dirPath: String): List<MediaFileData> = coroutineScope {
        // Read manifest
        val manifest = readJson<Map<String, MediaManifestEntry>>("$dirPath/$MANIFEST_FILE")
            ?: return@coroutineScope emptyList()
        
        // Read each file in parallel
        manifest.map { (hash, entry) ->
            async {
                val ext = mimeTypeToExtension(entry.mimeType)
                val safeHash = hash.replace("+", "-").replace("/", "_").trimEnd('=')
                val bytes = readBinary("$dirPath/$safeHash.$ext")
                
                bytes?.let {
                    MediaFileData(hash, it, entry.mimeType, entry.cssSelectors)
                }
            }
        }.awaitAll().filterNotNull()
    }
    
    private suspend fun deleteDirectory(dirPath: String): Int = withContext(dispatchers.io) {
        var deletedCount = 0
        
        try {
            val blobs = storage.list(
                bucketName,
                Storage.BlobListOption.prefix("$dirPath/")
            )
            
            for (blob in blobs.iterateAll()) {
                try {
                    if (storage.delete(blob.blobId)) {
                        deletedCount++
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to delete blob {}: {}", blob.name, e.message)
                }
            }
            
            logger.debug("Deleted {} files from {}", deletedCount, dirPath)
        } catch (e: Exception) {
            logger.error("Failed to delete directory {}: {}", dirPath, e.message)
        }
        
        deletedCount
    }
    
    private fun mimeTypeToExtension(mimeType: String): String = when {
        mimeType.contains("webp") -> "webp"
        mimeType.contains("png") -> "png"
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("gif") -> "gif"
        mimeType.contains("svg") -> "svg"
        else -> "bin"
    }
    
    private fun extensionToMimeType(ext: String): String = when (ext) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

// ==================== Serializable DTOs for GCS ====================

@kotlinx.serialization.Serializable
private data class SnapshotMetadata(
    val title: String?,
    val description: String?
)

@kotlinx.serialization.Serializable
private data class SerializableBoundingBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

@kotlinx.serialization.Serializable
private data class MediaManifestEntry(
    val mimeType: String,
    val cssSelectors: List<String>
)

// ==================== Hidden Container Bounding Boxes ====================

@kotlinx.serialization.Serializable
private data class SerializableHiddenContainerBoundingBoxes(
    val hiddenContainers: List<SerializableHiddenContainerBoundingBoxData>,
    val hiddenContainerCount: Int,
    val totalElementsCaptured: Int
)

@kotlinx.serialization.Serializable
private data class SerializableHiddenContainerBoundingBoxData(
    val containerId: String,
    val containerBox: SerializableBoundingBox,
    val elements: Map<String, SerializableBoundingBox>
)

private fun IBrowserPage.HiddenContainerBoundingBoxes.toSerializable() = SerializableHiddenContainerBoundingBoxes(
    hiddenContainers = hiddenContainers.map { container ->
        SerializableHiddenContainerBoundingBoxData(
            containerId = container.containerId,
            containerBox = SerializableBoundingBox(
                container.containerBox.left, 
                container.containerBox.top, 
                container.containerBox.right, 
                container.containerBox.bottom
            ),
            elements = container.elements.mapValues { (_, bbox) ->
                SerializableBoundingBox(bbox.left, bbox.top, bbox.right, bbox.bottom)
            }
        )
    },
    hiddenContainerCount = hiddenContainerCount,
    totalElementsCaptured = totalElementsCaptured
)

private fun SerializableHiddenContainerBoundingBoxes.toDomain() = IBrowserPage.HiddenContainerBoundingBoxes(
    hiddenContainers = hiddenContainers.map { container ->
        IBrowserPage.HiddenContainerBoundingBoxData(
            containerId = container.containerId,
            containerBox = IBrowserPage.BoundingBox(
                container.containerBox.left,
                container.containerBox.top,
                container.containerBox.right,
                container.containerBox.bottom
            ),
            elements = container.elements.mapValues { (_, bbox) ->
                IBrowserPage.BoundingBox(bbox.left, bbox.top, bbox.right, bbox.bottom)
            }
        )
    },
    hiddenContainerCount = hiddenContainerCount,
    totalElementsCaptured = totalElementsCaptured
)
