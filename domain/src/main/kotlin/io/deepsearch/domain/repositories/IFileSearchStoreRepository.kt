package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.FileSearchStore

/**
 * Repository for managing file search store mappings.
 * 
 * Stores the relationship between domains and their Gemini File Search Store
 * resource names.
 */
interface IFileSearchStoreRepository {
    
    /**
     * Find a file search store by domain.
     * 
     * @param domain The domain (e.g., "docs.example.com")
     * @return The file search store if found, null otherwise
     */
    suspend fun findByDomain(domain: String): FileSearchStore?
    
    /**
     * Find a file search store by its Gemini store name.
     * 
     * @param geminiStoreName The Gemini resource name
     * @return The file search store if found, null otherwise
     */
    suspend fun findByGeminiStoreName(geminiStoreName: String): FileSearchStore?
    
    /**
     * Create a new file search store mapping.
     * 
     * @param store The file search store to create
     * @return The created file search store with ID populated
     */
    suspend fun create(store: FileSearchStore): FileSearchStore
    
    /**
     * Update an existing file search store mapping.
     * 
     * @param store The file search store to update
     * @return The updated file search store
     */
    suspend fun update(store: FileSearchStore): FileSearchStore
    
    /**
     * Delete a file search store mapping.
     * 
     * @param store The file search store to delete
     */
    suspend fun delete(store: FileSearchStore)
    
    /**
     * List all file search stores.
     * 
     * @return All file search stores
     */
    suspend fun findAll(): List<FileSearchStore>
}

