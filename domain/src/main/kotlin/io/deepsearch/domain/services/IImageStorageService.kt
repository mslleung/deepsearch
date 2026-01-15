package io.deepsearch.domain.services

/**
 * Service for persistent image storage.
 * 
 * Used for storing extracted webpage images with their interpreted text.
 * Unlike [ITemporaryFileStorageService], these files are permanent cache entries.
 * 
 * Implementation should use cloud storage (e.g., GCS) for:
 * - Efficient binary storage (no Base64 overhead)
 * - CDN-like performance via signed URLs
 * - Reduced database bloat
 * - Cost-effective storage
 * 
 * Images are keyed by their content hash for deduplication across pages.
 */
interface IImageStorageService {
    
    /**
     * Store an image.
     * 
     * @param hash Content hash of the image (SHA-256, used as unique key)
     * @param bytes Raw image bytes
     * @param mimeType MIME type of the image (e.g., "image/png", "image/webp")
     * @return Storage path that can be used with [getSignedUrl]
     */
    suspend fun store(
        hash: ByteArray,
        bytes: ByteArray,
        mimeType: String
    ): String
    
    /**
     * Store multiple images in batch.
     * More efficient than individual [store] calls for bulk operations.
     * 
     * @param images List of images to store
     * @return Map of hash -> storage path for successfully stored images
     */
    suspend fun storeBatch(
        images: List<ImageToStore>
    ): Map<String, String>
    
    /**
     * Generate a signed URL for accessing an image.
     * The URL is time-limited and can be used by clients to fetch the image directly.
     * 
     * @param storagePath Path returned from [store]
     * @param expiryMinutes How long the URL should be valid (default: 60 minutes)
     * @return Signed URL that can be used to fetch the image
     */
    suspend fun getSignedUrl(
        storagePath: String,
        expiryMinutes: Int = 60
    ): String
    
    /**
     * Generate signed URLs for multiple images in batch.
     * More efficient than individual [getSignedUrl] calls.
     * 
     * @param storagePaths List of paths to generate URLs for
     * @param expiryMinutes How long the URLs should be valid
     * @return Map of storage path -> signed URL
     */
    suspend fun getSignedUrls(
        storagePaths: List<String>,
        expiryMinutes: Int = 60
    ): Map<String, String>
    
    /**
     * Check if an image exists in storage.
     * 
     * @param storagePath Path returned from [store]
     * @return true if the image exists
     */
    suspend fun exists(storagePath: String): Boolean
    
    /**
     * Delete an image from storage.
     * 
     * @param storagePath Path returned from [store]
     * @return true if deleted, false if not found
     */
    suspend fun delete(storagePath: String): Boolean
}

/**
 * Data class for batch image storage.
 */
data class ImageToStore(
    val hash: ByteArray,
    val bytes: ByteArray,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageToStore
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = hash.contentHashCode()
}
