package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.valueobjects.UserId

/**
 * Repository for managing raw (unencrypted) API keys.
 * 
 * This is used specifically for playground API keys where we need to retrieve
 * the original key value for display in the web application.
 */
interface IRawApiKeyRepository {
    /**
     * Saves a raw API key for a user.
     * The key will be encrypted before storage.
     * 
     * @param userId The user ID
     * @param rawKey The raw API key to store
     */
    suspend fun save(userId: UserId, rawKey: String)
    
    /**
     * Finds the raw API key for a user.
     * The key will be decrypted before returning.
     * 
     * @param userId The user ID
     * @return The raw API key, or null if not found
     */
    suspend fun findByUserId(userId: UserId): String?
    
    /**
     * Deletes the raw API key for a user.
     * 
     * @param userId The user ID
     * @return true if the key was deleted, false if it didn't exist
     */
    suspend fun delete(userId: UserId): Boolean
}

