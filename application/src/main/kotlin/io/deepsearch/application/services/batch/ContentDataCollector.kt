package io.deepsearch.application.services.batch

import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.models.valueobjects.BatchUrlStateId
import io.deepsearch.domain.models.valueobjects.MediaHash
import io.deepsearch.domain.services.IBatchSnapshotStorageService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Phase 1 of ContentLlmBatchHandler: Collect content data from GCS storage.
 * Reads HTML, icons, images, and screenshots from GCS for all URL states.
 */
class ContentDataCollector(
    private val snapshotStorage: IBatchSnapshotStorageService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Collect content data from GCS storage for all URL states.
     *
     * @param urlStates List of URL states needing content LLM processing
     * @return Aggregated collection result with per-URL data and deduplicated media
     */
    suspend fun collect(urlStates: List<BatchUrlState>): ContentCollectionResult {
        val urlPages = mutableMapOf<BatchUrlStateId, UrlPageData>()
        val uniqueIcons = mutableMapOf<MediaHash, GcsIconData>()
        val uniqueImages = mutableMapOf<MediaHash, GcsImageData>()

        urlStates.forEach { urlState ->
            try {
                val basePath = urlState.snapshotBasePath ?: return@forEach
                val urlStateId = BatchUrlStateId(urlState.id!!)

                // Read HTML from GCS
                val html = snapshotStorage.readHtml(basePath) ?: return@forEach

                // Read bounding boxes from GCS
                val boundingBoxes = snapshotStorage.readBoundingBoxes(basePath) ?: emptyMap()

                // Read screenshot from GCS
                val screenshot = snapshotStorage.readScreenshot(basePath)

                // Read icons from GCS and deduplicate by hash
                val icons = snapshotStorage.readIcons(basePath)
                val iconHashes = mutableListOf<MediaHash>()
                icons.forEach { icon ->
                    val hash = MediaHash(icon.hash)
                    uniqueIcons.putIfAbsent(hash, GcsIconData(icon.bytes, icon.mimeType, icon.cssSelectors))
                    iconHashes.add(hash)
                }

                // Read images from GCS and deduplicate by hash
                val images = snapshotStorage.readImages(basePath)
                val imageHashes = mutableListOf<MediaHash>()
                images.forEach { image ->
                    val hash = MediaHash(image.hash)
                    uniqueImages.putIfAbsent(hash, GcsImageData(image.bytes, image.mimeType, image.cssSelectors))
                    imageHashes.add(hash)
                }

                urlPages[urlStateId] = UrlPageData(
                    urlStateId = urlStateId,
                    basePath = basePath,
                    html = html,
                    boundingBoxes = boundingBoxes,
                    iconHashes = iconHashes,
                    imageHashes = imageHashes,
                    screenshotBytes = screenshot?.bytes,
                    screenshotMimeType = screenshot?.mimeType
                )
            } catch (e: Exception) {
                logger.warn("Failed to collect data for {}: {}", urlState.url, e.message)
            }
        }

        return ContentCollectionResult(urlPages, uniqueIcons, uniqueImages)
    }
}
