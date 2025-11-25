package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Persistent record of a periodic index job. Encapsulates allowed state transitions
 * and progress updates. Jobs are immutable in identity; state and counters can
 * evolve over time but deletion is not allowed.
 */
@OptIn(ExperimentalTime::class)
class PeriodicIndexJob(
    var id: Long? = null,
    val userId: UserId,
    val baseUrl: String,
    val maxUrlCount: Int,
    val sitemapUrl: String? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var processedCount: Int = 0,
    var state: PeriodicIndexJobState = PeriodicIndexJobState.IN_PROGRESS,
    var version: Long = 0
) {

    fun incrementProcessed() {
        if (state != PeriodicIndexJobState.IN_PROGRESS) return
        if (processedCount < maxUrlCount) {
            processedCount += 1
        }
        updatedAt = Clock.System.now()
        if (processedCount >= maxUrlCount) {
            state = PeriodicIndexJobState.COMPLETED
        }
    }

    fun markStopped() {
        if (state == PeriodicIndexJobState.IN_PROGRESS) {
            state = PeriodicIndexJobState.STOPPED
            updatedAt = Clock.System.now()
        }
    }

    fun markCompleted() {
        state = PeriodicIndexJobState.COMPLETED
        updatedAt = Clock.System.now()
    }
}

enum class PeriodicIndexJobState {
    IN_PROGRESS,
    COMPLETED,
    STOPPED
}

