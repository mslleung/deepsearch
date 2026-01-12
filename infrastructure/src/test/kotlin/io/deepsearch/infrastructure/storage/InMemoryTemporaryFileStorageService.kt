package io.deepsearch.infrastructure.storage

import io.deepsearch.domain.services.ITemporaryFileStorageService
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ITemporaryFileStorageService for testing.
 * Files are stored in memory and lost when the test completes.
 */
class InMemoryTemporaryFileStorageService : ITemporaryFileStorageService {
    
    private val storage = ConcurrentHashMap<String, ByteArray>()
    
    override suspend fun store(
        jobId: Long,
        fileHash: String,
        bytes: ByteArray,
        mimeType: String
    ): String {
        val storagePath = "job-$jobId/$fileHash"
        storage[storagePath] = bytes
        return storagePath
    }
    
    override suspend fun retrieve(storagePath: String): ByteArray? {
        return storage[storagePath]
    }
    
    override suspend fun delete(storagePath: String): Boolean {
        return storage.remove(storagePath) != null
    }
    
    override suspend fun deleteAllForJob(jobId: Long): Int {
        val prefix = "job-$jobId/"
        val keysToDelete = storage.keys.filter { it.startsWith(prefix) }
        keysToDelete.forEach { storage.remove(it) }
        return keysToDelete.size
    }
    
    override suspend fun exists(storagePath: String): Boolean {
        return storage.containsKey(storagePath)
    }
    
    /**
     * Clear all stored files. Useful for test cleanup.
     */
    fun clear() {
        storage.clear()
    }
}
