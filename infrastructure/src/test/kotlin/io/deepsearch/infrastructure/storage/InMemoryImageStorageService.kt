package io.deepsearch.infrastructure.storage

import io.deepsearch.domain.services.IImageStorageService
import io.deepsearch.domain.services.ImageToStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * In-memory implementation of IImageStorageService for testing.
 * Files are stored in memory and lost when the test completes.
 * 
 * "Signed URLs" are just the storage path prefixed with "http://test-bucket/".
 */
@OptIn(ExperimentalEncodingApi::class)
class InMemoryImageStorageService : IImageStorageService {
    
    private data class StoredImage(
        val bytes: ByteArray,
        val mimeType: String
    )
    
    private val storage = ConcurrentHashMap<String, StoredImage>()
    
    override suspend fun store(
        hash: ByteArray,
        bytes: ByteArray,
        mimeType: String
    ): String {
        val storagePath = buildPath(hash)
        storage[storagePath] = StoredImage(bytes, mimeType)
        return storagePath
    }
    
    override suspend fun storeBatch(
        images: List<ImageToStore>
    ): Map<String, String> {
        return images.associate { image ->
            val path = store(image.hash, image.bytes, image.mimeType)
            Base64.UrlSafe.encode(image.hash) to path
        }
    }
    
    override suspend fun getSignedUrl(
        storagePath: String,
        expiryMinutes: Int
    ): String {
        // Return a fake signed URL for testing
        return "http://test-bucket/$storagePath?expiry=$expiryMinutes"
    }
    
    override suspend fun getSignedUrls(
        storagePaths: List<String>,
        expiryMinutes: Int
    ): Map<String, String> {
        return storagePaths.associateWith { path ->
            getSignedUrl(path, expiryMinutes)
        }
    }
    
    override suspend fun exists(storagePath: String): Boolean {
        return storage.containsKey(storagePath)
    }
    
    override suspend fun delete(storagePath: String): Boolean {
        return storage.remove(storagePath) != null
    }
    
    /**
     * Retrieve image bytes (for testing verification).
     */
    fun retrieve(storagePath: String): ByteArray? {
        return storage[storagePath]?.bytes
    }
    
    /**
     * Get the stored mime type (for testing verification).
     */
    fun getMimeType(storagePath: String): String? {
        return storage[storagePath]?.mimeType
    }
    
    /**
     * Clear all stored files. Useful for test cleanup.
     */
    fun clear() {
        storage.clear()
    }
    
    /**
     * Get the number of stored images (for testing).
     */
    fun size(): Int = storage.size
    
    private fun buildPath(hash: ByteArray): String {
        val urlSafeHash = Base64.UrlSafe.encode(hash).trimEnd('=')
        return "images/$urlSafeHash"
    }
}
