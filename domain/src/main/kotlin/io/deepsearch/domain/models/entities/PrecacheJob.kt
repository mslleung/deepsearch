package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Persistent record of a precache job. Encapsulates allowed state transitions
 * and progress updates. Jobs are immutable in identity; state and counters can
 * evolve over time but deletion is not allowed.
 */
@OptIn(ExperimentalTime::class)
class PrecacheJob(
    var id: Long? = null,
    val userId: UserId? = null, // Nullable for backward compatibility
    val baseUrl: String,
    val maxUrlCount: Int,
    val sitemapUrl: String? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var processedCount: Int = 0,
    var state: PrecacheJobState = PrecacheJobState.IN_PROGRESS,
    var version: Long = 0
) {

    fun incrementProcessed() {
        if (state != PrecacheJobState.IN_PROGRESS) return
        if (processedCount < maxUrlCount) {
            processedCount += 1
        }
        updatedAt = Clock.System.now()
        if (processedCount >= maxUrlCount) {
            state = PrecacheJobState.COMPLETED
        }
    }

    fun markStopped() {
        if (state == PrecacheJobState.IN_PROGRESS) {
            state = PrecacheJobState.STOPPED
            updatedAt = Clock.System.now()
        }
    }

    fun markCompleted() {
        state = PrecacheJobState.COMPLETED
        updatedAt = Clock.System.now()
    }
}

enum class PrecacheJobState {
    IN_PROGRESS,
    COMPLETED,
    STOPPED
}
