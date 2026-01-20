package io.deepsearch.infrastructure.storage

import io.deepsearch.domain.agents.MobileLayoutIdentification
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.valueobjects.SemanticElements
import io.deepsearch.domain.services.*
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of IBatchSnapshotStorageService for testing.
 * Stores all data in memory instead of GCS.
 */
class InMemoryBatchSnapshotStorageService : IBatchSnapshotStorageService {
    
    // Storage maps keyed by base path
    private val htmlStorage = ConcurrentHashMap<String, String>()
    private val screenshotStorage = ConcurrentHashMap<String, ScreenshotData>()
    private val boundingBoxStorage = ConcurrentHashMap<String, Map<String, IBrowserPage.BoundingBox>>()
    private val iconStorage = ConcurrentHashMap<String, List<MediaFileData>>()
    private val imageStorage = ConcurrentHashMap<String, List<MediaFileData>>()
    private val cleanedHtmlStorage = ConcurrentHashMap<String, String>()
    private val semanticElementsStorage = ConcurrentHashMap<String, SemanticElements>()
    private val tableIdentificationsStorage = ConcurrentHashMap<String, List<TableIdentification>>()
    private val hiddenMobileLayoutsStorage = ConcurrentHashMap<String, List<MobileLayoutIdentification>>()
    private val iconInterpretationsStorage = ConcurrentHashMap<String, Map<String, String?>>()
    private val imageTextsStorage = ConcurrentHashMap<String, Map<String, String?>>()
    private val tableMarkdownsStorage = ConcurrentHashMap<String, Map<String, String>>()
    private val kgExtractionStorage = ConcurrentHashMap<String, KgExtractionResult>()
    private val metadataStorage = ConcurrentHashMap<String, Pair<String?, String?>>() // title, description
    
    override suspend fun storeExtraction(
        jobId: Long,
        urlHash: String,
        extraction: ExtractionData
    ): String {
        val basePath = "batch-snapshots/$jobId/$urlHash"
        
        htmlStorage[basePath] = extraction.html
        metadataStorage[basePath] = extraction.title to extraction.description
        
        if (extraction.boundingBoxes.isNotEmpty()) {
            boundingBoxStorage[basePath] = extraction.boundingBoxes
        }
        
        extraction.screenshot?.let {
            screenshotStorage[basePath] = it
        }
        
        if (extraction.icons.isNotEmpty()) {
            iconStorage[basePath] = extraction.icons.map { icon ->
                MediaFileData(icon.hash, icon.bytes, icon.mimeType, icon.cssSelectors)
            }
        }
        
        if (extraction.images.isNotEmpty()) {
            imageStorage[basePath] = extraction.images.map { image ->
                MediaFileData(image.hash, image.bytes, image.mimeType, image.cssSelectors)
            }
        }
        
        return basePath
    }
    
    override suspend fun readHtml(basePath: String): String? = htmlStorage[basePath]
    
    override suspend fun readScreenshot(basePath: String): ScreenshotData? = screenshotStorage[basePath]
    
    override suspend fun readIcons(basePath: String): List<MediaFileData> = iconStorage[basePath] ?: emptyList()
    
    override suspend fun readImages(basePath: String): List<MediaFileData> = imageStorage[basePath] ?: emptyList()
    
    override suspend fun storeContentLlmResults(basePath: String, results: ContentLlmResults) {
        results.cleanedHtml?.let { cleanedHtmlStorage[basePath] = it }
        results.semanticElements?.let { semanticElementsStorage[basePath] = it }
        results.tableIdentifications?.let { tableIdentificationsStorage[basePath] = it }
        results.hiddenMobileLayouts?.let { hiddenMobileLayoutsStorage[basePath] = it }
        results.iconInterpretations?.let { iconInterpretationsStorage[basePath] = it }
        results.imageTexts?.let { imageTextsStorage[basePath] = it }
    }
    
    override suspend fun deleteIcons(basePath: String) {
        iconStorage.remove(basePath)
    }
    
    override suspend fun readBoundingBoxes(basePath: String): Map<String, IBrowserPage.BoundingBox>? =
        boundingBoxStorage[basePath]
    
    override suspend fun readTableIdentifications(basePath: String): List<TableIdentification>? =
        tableIdentificationsStorage[basePath]
    
    override suspend fun storeTableMarkdowns(basePath: String, tableMarkdowns: Map<String, String>) {
        tableMarkdownsStorage[basePath] = tableMarkdowns
    }
    
    override suspend fun deleteImages(basePath: String) {
        imageStorage.remove(basePath)
    }
    
    override suspend fun readForCaching(basePath: String): CachingData? {
        val html = htmlStorage[basePath] ?: return null
        
        return CachingData(
            html = html,
            cleanedHtml = cleanedHtmlStorage[basePath],
            semanticElements = semanticElementsStorage[basePath],
            tableIdentifications = tableIdentificationsStorage[basePath],
            hiddenMobileLayouts = hiddenMobileLayoutsStorage[basePath],
            tableMarkdowns = tableMarkdownsStorage[basePath],
            iconInterpretations = iconInterpretationsStorage[basePath],
            imageTexts = imageTextsStorage[basePath],
            boundingBoxes = boundingBoxStorage[basePath]
        )
    }
    
    override suspend fun storeKgExtractionResult(basePath: String, result: KgExtractionResult) {
        kgExtractionStorage[basePath] = result
    }
    
    override suspend fun readKgExtractionResult(basePath: String): KgExtractionResult? =
        kgExtractionStorage[basePath]
    
    override suspend fun deleteUrl(basePath: String): Int {
        var count = 0
        if (htmlStorage.remove(basePath) != null) count++
        if (screenshotStorage.remove(basePath) != null) count++
        if (boundingBoxStorage.remove(basePath) != null) count++
        if (iconStorage.remove(basePath) != null) count++
        if (imageStorage.remove(basePath) != null) count++
        if (cleanedHtmlStorage.remove(basePath) != null) count++
        if (semanticElementsStorage.remove(basePath) != null) count++
        if (tableIdentificationsStorage.remove(basePath) != null) count++
        if (hiddenMobileLayoutsStorage.remove(basePath) != null) count++
        if (iconInterpretationsStorage.remove(basePath) != null) count++
        if (imageTextsStorage.remove(basePath) != null) count++
        if (tableMarkdownsStorage.remove(basePath) != null) count++
        if (kgExtractionStorage.remove(basePath) != null) count++
        if (metadataStorage.remove(basePath) != null) count++
        return count
    }
    
    override suspend fun deleteJob(jobId: Long): Int {
        val prefix = "batch-snapshots/$jobId/"
        var count = 0
        
        listOf(
            htmlStorage, screenshotStorage, boundingBoxStorage, iconStorage, imageStorage,
            cleanedHtmlStorage, semanticElementsStorage, tableIdentificationsStorage,
            hiddenMobileLayoutsStorage, iconInterpretationsStorage, imageTextsStorage,
            tableMarkdownsStorage, kgExtractionStorage, metadataStorage
        ).forEach { storage ->
            val keysToRemove = storage.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { key ->
                if (storage.remove(key) != null) count++
            }
        }
        
        return count
    }
    
    override suspend fun exists(basePath: String): Boolean = htmlStorage.containsKey(basePath)
    
    // Test helper methods
    fun clear() {
        htmlStorage.clear()
        screenshotStorage.clear()
        boundingBoxStorage.clear()
        iconStorage.clear()
        imageStorage.clear()
        cleanedHtmlStorage.clear()
        semanticElementsStorage.clear()
        tableIdentificationsStorage.clear()
        hiddenMobileLayoutsStorage.clear()
        iconInterpretationsStorage.clear()
        imageTextsStorage.clear()
        tableMarkdownsStorage.clear()
        kgExtractionStorage.clear()
        metadataStorage.clear()
    }
    
    fun size(): Int = htmlStorage.size
}
