package io.deepsearch.infrastructure.storage

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.deepsearch.domain.config.GcsConfig
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.services.IImageStorageService
import io.deepsearch.domain.services.ImageToStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Google Cloud Storage implementation of image storage service.
 * 
 * Stores webpage images in GCS for:
 * - Efficient binary storage (no Base64 overhead like PostgreSQL text columns)
 * - CDN-like performance via signed URLs
 * - Reduced database size
 * 
 * File organization: images/{hash}
 * Where hash is the URL-safe Base64 encoded SHA-256 hash of the image content.
 * 
 * Authentication: Uses Application Default Credentials (ADC).
 * Set GOOGLE_APPLICATION_CREDENTIALS environment variable to service account JSON path,
 * or run on GCE/Cloud Run where credentials are automatic.
 * 
 * @param gcsConfig GCS configuration containing bucket names
 * @param dispatchers Dispatcher provider for IO operations
 */
@OptIn(ExperimentalEncodingApi::class)
class GcsImageStorageService(
    gcsConfig: GcsConfig,
    private val dispatchers: IDispatcherProvider
) : IImageStorageService {
    
    private val bucketName = gcsConfig.imageBucketName
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    // Lazy initialization - only create client when needed
    private val storage: Storage by lazy {
        StorageOptions.getDefaultInstance().service
    }
    
    companion object {
        private const val PREFIX = "images"
    }
    
    override suspend fun store(
        hash: ByteArray,
        bytes: ByteArray,
        mimeType: String
    ): String = withContext(dispatchers.io) {
        val path = buildPath(hash)
        val blobId = BlobId.of(bucketName, path)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(mimeType)
            .build()
        
        try {
            storage.create(blobInfo, bytes)
            logger.debug("Stored image: gs://{}/{} ({} bytes, {})", bucketName, path, bytes.size, mimeType)
            path
        } catch (e: Exception) {
            logger.error("Failed to store image gs://{}/{}: {}", bucketName, path, e.message)
            throw e
        }
    }
    
    override suspend fun storeBatch(
        images: List<ImageToStore>
    ): Map<String, String> = coroutineScope {
        if (images.isEmpty()) {
            return@coroutineScope emptyMap()
        }
        
        logger.debug("Storing {} images in batch", images.size)
        
        val results = images.map { image ->
            async {
                try {
                    val path = store(image.hash, image.bytes, image.mimeType)
                    Base64.UrlSafe.encode(image.hash) to path
                } catch (e: Exception) {
                    logger.warn("Failed to store image in batch: {}", e.message)
                    null
                }
            }
        }.awaitAll()
        
        results.filterNotNull().toMap().also { 
            logger.debug("Successfully stored {} of {} images", it.size, images.size)
        }
    }
    
    override suspend fun getSignedUrl(
        storagePath: String,
        expiryMinutes: Int
    ): String = withContext(dispatchers.io) {
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).build()
        
        try {
            val url = storage.signUrl(
                blobInfo,
                expiryMinutes.toLong(),
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature()
            )
            logger.debug("Generated signed URL for gs://{}/{} (expires in {} min)", bucketName, storagePath, expiryMinutes)
            url.toString()
        } catch (e: Exception) {
            logger.error("Failed to generate signed URL for gs://{}/{}: {}", bucketName, storagePath, e.message)
            throw e
        }
    }
    
    override suspend fun getSignedUrls(
        storagePaths: List<String>,
        expiryMinutes: Int
    ): Map<String, String> = coroutineScope {
        if (storagePaths.isEmpty()) {
            return@coroutineScope emptyMap()
        }
        
        val results = storagePaths.map { path ->
            async {
                try {
                    path to getSignedUrl(path, expiryMinutes)
                } catch (e: Exception) {
                    logger.warn("Failed to generate signed URL for {}: {}", path, e.message)
                    null
                }
            }
        }.awaitAll()
        
        results.filterNotNull().toMap()
    }
    
    override suspend fun exists(storagePath: String): Boolean = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, storagePath)
        
        try {
            val blob = storage.get(blobId)
            blob != null
        } catch (e: Exception) {
            logger.warn("Failed to check existence of gs://{}/{}: {}", bucketName, storagePath, e.message)
            false
        }
    }
    
    override suspend fun delete(storagePath: String): Boolean = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, storagePath)
        
        try {
            val deleted = storage.delete(blobId)
            if (deleted) {
                logger.debug("Deleted image: gs://{}/{}", bucketName, storagePath)
            }
            deleted
        } catch (e: Exception) {
            logger.warn("Failed to delete image gs://{}/{}: {}", bucketName, storagePath, e.message)
            false
        }
    }
    
    /**
     * Build the GCS path for an image hash.
     * Uses URL-safe Base64 encoding to ensure the path is valid.
     */
    private fun buildPath(hash: ByteArray): String {
        val urlSafeHash = Base64.UrlSafe.encode(hash).trimEnd('=')
        return "$PREFIX/$urlSafeHash"
    }
}
