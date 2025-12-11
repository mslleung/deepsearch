package io.deepsearch.application.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

interface IUrlProcessingLockRegistry {

    suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T

}

/**
 * Registry of per-key coroutine mutexes to deduplicate concurrent work across requests.
 * Keys should be stable identifiers such as normalized URLs.
 */
class UrlProcessingLockRegistry : IUrlProcessingLockRegistry {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Executes [block] while holding a lock for [key]. Ensures only one concurrent execution per key.
     * Best-effort cleans up idle mutexes after execution to avoid unbounded growth.
     */
    override suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T {
        val mutex = mutexes.computeIfAbsent(key) { Mutex() }
        return try {
            mutex.withLock { block() }
        } finally {
            // Cleanup after releasing the lock - check if mutex is idle
            if (!mutex.isLocked) {
                mutexes.remove(key, mutex)
            }
        }
    }
}


