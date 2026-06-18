package io.deepsearch.infrastructure.storage

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.deepsearch.domain.config.GcsConfig
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.services.IIterationScreenshotStorage
import io.deepsearch.domain.services.IterationScreenshotPath
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * GCS implementation of [IIterationScreenshotStorage].
 *
 * Stores iteration screenshots in the temp bucket under a prefix that supports
 * future querying by session and URL:
 *   gs://{tempBucket}/agentic-nav/{sessionId}/{urlHash}/iter-{nn}-{type}.{ext}
 *
 * The temp bucket already has lifecycle policies for automatic cleanup.
 */
class GcsIterationScreenshotStorage(
    gcsConfig: GcsConfig,
    private val dispatchers: IDispatcherProvider
) : IIterationScreenshotStorage {

    private val bucketName = gcsConfig.tempBucketName
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val storage: Storage by lazy { StorageOptions.getDefaultInstance().service }

    override suspend fun saveRawScreenshot(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        bytes: ByteArray,
        mimeType: ImageMimeType
    ) {
        val path = IterationScreenshotPath.rawPath(sessionId, url, iteration, mimeType)
        upload(path, mimeType.value, bytes)
    }

    override suspend fun saveAnnotatedScreenshot(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        bytes: ByteArray
    ) {
        val path = IterationScreenshotPath.annotatedPath(sessionId, url, iteration)
        upload(path, "image/jpeg", bytes)
    }

    override suspend fun saveRegionCrop(
        sessionId: SessionId,
        url: String,
        iteration: Int,
        regionIndex: Int,
        bytes: ByteArray,
        description: String
    ) {
        val path = IterationScreenshotPath.regionCropPath(sessionId, url, iteration, regionIndex)
        upload(path, "image/png", bytes)
    }

    private suspend fun upload(path: String, contentType: String, bytes: ByteArray) {
        try {
            withContext(dispatchers.io) {
                val blobId = BlobId.of(bucketName, path)
                val blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(contentType)
                    .build()
                storage.create(blobInfo, bytes)
                logger.debug("Saved iteration screenshot: gs://{}/{} ({} bytes)", bucketName, path, bytes.size)
            }
        } catch (e: Exception) {
            logger.warn("Failed to upload iteration screenshot {}: {}", path, e.message)
        }
    }
}
