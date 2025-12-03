package io.deepsearch.domain.services

import io.deepsearch.domain.models.valueobjects.FileSearchResult
import io.deepsearch.domain.models.valueobjects.FileSearchStoreInfo
import io.deepsearch.domain.models.valueobjects.GeminiFileInfo

/**
 * Service for interacting with Gemini File Search stores.
 * 
 * Provides RAG capabilities by uploading files to Gemini's managed file search
 * system and querying them for relevant content. Uses file metadata for:
 * - Deduplication via file hash
 * - Cache expiry via upload timestamp
 * - Citation enrichment via source URL
 */
interface IGeminiFileSearchService {

    /**
     * Get an existing file search store for a domain, or create a new one.
     * 
     * @param domain The domain (e.g., "docs.example.com") to associate with the store
     * @return Information about the store
     */
    suspend fun getOrCreateStore(domain: String): FileSearchStoreInfo

    /**
     * Find a file in the store by its hash.
     * Uses the custom metadata stored with each file to locate it.
     * 
     * @param storeName The Gemini store resource name
     * @param fileHash SHA-256 hash of the file content
     * @return File info if found, null otherwise
     */
    suspend fun findFileByHash(storeName: String, fileHash: String): GeminiFileInfo?

    /**
     * Upload a file to the file search store.
     * The file is automatically chunked, embedded, and indexed by Gemini.
     * 
     * @param storeName The Gemini store resource name
     * @param fileBytes The file content
     * @param mimeType MIME type of the file
     * @param sourceUrl Original URL where the file was found
     * @param fileHash SHA-256 hash of the file content
     * @return Information about the uploaded file
     */
    suspend fun uploadFile(
        storeName: String,
        fileBytes: ByteArray,
        mimeType: String,
        sourceUrl: String,
        fileHash: String
    ): GeminiFileInfo

    /**
     * Query a file search store for relevant content.
     * 
     * @param storeName The Gemini store resource name
     * @param query The search query
     * @param maxAgeMs Optional maximum age in milliseconds; results from files 
     *                 uploaded before this threshold are filtered out client-side
     * @return Search results with content chunks and citations
     */
    suspend fun queryStore(
        storeName: String,
        query: String,
        maxAgeMs: Long? = null
    ): FileSearchResult

    /**
     * Delete a file from the store.
     * Used when re-indexing expired files.
     * 
     * @param storeName The Gemini store resource name
     * @param fileName The Gemini file resource name to delete
     */
    suspend fun deleteFile(storeName: String, fileName: String)

    /**
     * List all files in a store.
     * Useful for cache management and debugging.
     * 
     * @param storeName The Gemini store resource name
     * @return List of files in the store
     */
    suspend fun listFiles(storeName: String): List<GeminiFileInfo>
}

