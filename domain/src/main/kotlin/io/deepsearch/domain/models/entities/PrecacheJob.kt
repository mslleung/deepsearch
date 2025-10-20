package io.deepsearch.domain.models.entities

/**
 * Persistent record of a precache job. Encapsulates allowed state transitions
 * and progress updates. Jobs are immutable in identity; state and counters can
 * evolve over time but deletion is not allowed.
 */
class PrecacheJob(
    val id: Long? = null,
    val baseUrl: String,
    val maxUrlCount: Int,
    val createdAtMs: Long,
    var updatedAtMs: Long,
    var processedCount: Int = 0,
    var state: PrecacheJobState = PrecacheJobState.IN_PROGRESS
) {

    fun withId(newId: Long): PrecacheJob = PrecacheJob(
        id = newId,
        baseUrl = baseUrl,
        maxUrlCount = maxUrlCount,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        processedCount = processedCount,
        state = state
    )

    fun incrementProcessed(nowMs: Long) {
        if (state != PrecacheJobState.IN_PROGRESS) return
        if (processedCount < maxUrlCount) {
            processedCount += 1
        }
        updatedAtMs = nowMs
        if (processedCount >= maxUrlCount) {
            state = PrecacheJobState.COMPLETED
        }
    }

    fun markStopped(nowMs: Long) {
        if (state == PrecacheJobState.IN_PROGRESS) {
            state = PrecacheJobState.STOPPED
            updatedAtMs = nowMs
        }
    }

    fun markCompleted(nowMs: Long) {
        state = PrecacheJobState.COMPLETED
        updatedAtMs = nowMs
    }
}

enum class PrecacheJobState {
    IN_PROGRESS,
    COMPLETED,
    STOPPED
}
