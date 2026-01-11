package io.deepsearch.domain.services

/**
 * Service for temporary file storage during batch processing.
 * 
 * Files are stored temporarily while awaiting processing (e.g., upload to Gemini File Search).
 * Once processing completes, files should be deleted to free storage space.
 * 
 * Implementation should use cloud storage (e.g., GCS) for:
 * - Durability across server restarts
 * - Avoiding database bloat
 * - Cost-effective storage
 * 
 * Free tier considerations (GCS):
 * - 5 GB-months regional storage (US regions)
 * - 5,000 Class A operations/month (uploads)
 * - 50,000 Class B operations/month (downloads)
 */
interface ITemporaryFileStorageService {
    
    /**
     * Store a file temporarily.
     * 
     * @param jobId Batch job ID (used for organizing files)
     * @param fileHash SHA-256 hash of the file (used as filename for deduplication)
     * @param bytes File content
     * @param mimeType MIME type of the file
     * @return Storage path/key that can be used to retrieve the file
     */
    suspend fun store(
        jobId: Long,
        fileHash: String,
        bytes: ByteArray,
        mimeType: String
    ): String
    
    /**
     * Retrieve a stored file.
     * 
     * @param storagePath Path returned from [store]
     * @return File content, or null if not found
     */
    suspend fun retrieve(storagePath: String): ByteArray?
    
    /**
     * Delete a stored file.
     * Should be called after successful processing to free storage.
     * 
     * @param storagePath Path returned from [store]
     * @return true if deleted, false if not found
     */
    suspend fun delete(storagePath: String): Boolean
    
    /**
     * Delete all files for a batch job.
     * Called when a job completes or is cancelled.
     * 
     * @param jobId Batch job ID
     * @return Number of files deleted
     */
    suspend fun deleteAllForJob(jobId: Long): Int
    
    /**
     * Check if a file exists.
     * 
     * @param storagePath Path returned from [store]
     * @return true if exists
     */
    suspend fun exists(storagePath: String): Boolean
}
