package io.deepsearch.infrastructure.storage

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.deepsearch.domain.config.GcsConfig
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.services.ITemporaryFileStorageService
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Google Cloud Storage implementation of temporary file storage.
 * 
 * Uses GCS Free Tier (5 GB-months US regional storage) for cost-effective
 * temporary file storage during batch processing.
 * 
 * File organization: batch-files/{jobId}/{fileHash}
 * 
 * Authentication: Uses Application Default Credentials (ADC).
 * Set GOOGLE_APPLICATION_CREDENTIALS environment variable to service account JSON path,
 * or run on GCE/Cloud Run where credentials are automatic.
 * 
 * @param gcsConfig GCS configuration containing bucket names
 * @param dispatchers Dispatcher provider for IO operations
 */
class GcsTemporaryFileStorageService(
    gcsConfig: GcsConfig,
    private val dispatchers: IDispatcherProvider
) : ITemporaryFileStorageService {
    
    private val bucketName = gcsConfig.tempBucketName
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    // Lazy initialization - only create client when needed
    private val storage: Storage by lazy {
        StorageOptions.getDefaultInstance().service
    }
    
    companion object {
        private const val PREFIX = "batch-files"
    }
    
    override suspend fun store(
        jobId: Long,
        fileHash: String,
        bytes: ByteArray,
        mimeType: String
    ): String = withContext(dispatchers.io) {
        val path = buildPath(jobId, fileHash)
        val blobId = BlobId.of(bucketName, path)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(mimeType)
            .build()
        
        try {
            storage.create(blobInfo, bytes)
            logger.debug("Stored file: gs://{}/{} ({} bytes)", bucketName, path, bytes.size)
            path
        } catch (e: Exception) {
            logger.error("Failed to store file gs://{}/{}: {}", bucketName, path, e.message)
            throw e
        }
    }
    
    override suspend fun retrieve(storagePath: String): ByteArray? = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, storagePath)
        
        try {
            val blob = storage.get(blobId)
            if (blob == null) {
                logger.debug("File not found: gs://{}/{}", bucketName, storagePath)
                return@withContext null
            }
            
            val bytes = blob.getContent()
            logger.debug("Retrieved file: gs://{}/{} ({} bytes)", bucketName, storagePath, bytes.size)
            bytes
        } catch (e: Exception) {
            logger.error("Failed to retrieve file gs://{}/{}: {}", bucketName, storagePath, e.message)
            null
        }
    }
    
    override suspend fun delete(storagePath: String): Boolean = withContext(dispatchers.io) {
        val blobId = BlobId.of(bucketName, storagePath)
        
        try {
            val deleted = storage.delete(blobId)
            if (deleted) {
                logger.debug("Deleted file: gs://{}/{}", bucketName, storagePath)
            }
            deleted
        } catch (e: Exception) {
            logger.warn("Failed to delete file gs://{}/{}: {}", bucketName, storagePath, e.message)
            false
        }
    }
    
    override suspend fun deleteAllForJob(jobId: Long): Int = withContext(dispatchers.io) {
        val prefix = "$PREFIX/$jobId/"
        var deletedCount = 0
        
        try {
            val blobs = storage.list(
                bucketName,
                Storage.BlobListOption.prefix(prefix)
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
            
            logger.info("Deleted {} files for job {}", deletedCount, jobId)
        } catch (e: Exception) {
            logger.error("Failed to list/delete files for job {}: {}", jobId, e.message)
        }
        
        deletedCount
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
    
    private fun buildPath(jobId: Long, fileHash: String): String {
        // URL-safe hash (replace + with -, / with _)
        val safeHash = fileHash.replace("+", "-").replace("/", "_").trimEnd('=')
        return "$PREFIX/$jobId/$safeHash"
    }
}
