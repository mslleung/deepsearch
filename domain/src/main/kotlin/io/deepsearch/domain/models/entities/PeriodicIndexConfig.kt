package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.UserId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

enum class PeriodicIndexPeriod(val days: Int?) {
    WEEKLY(7),
    MONTHLY(30),
    QUARTERLY(90),
    YEARLY(365),
    ONE_OFF(null);

    companion object {
        fun fromDays(days: Int?): PeriodicIndexPeriod {
            return entries.find { it.days == days } ?: ONE_OFF
        }
    }
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexConfig(
    val id: Long? = null,
    val userId: UserId,
    var url: String,
    var periodDays: Int? = null, // null means one-off
    var enabled: Boolean = true,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    var updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    var lastRunAt: Long? = null,
    var version: Long = 0
) {
    /**
     * Calculates the next run time based on lastRunAt and periodDays.
     * Returns null if:
     * - Config is disabled
     * - One-off job that has already run
     * Returns current time if never run (should run immediately).
     * Otherwise returns lastRunAt + periodDays.
     */
    val nextRunAt: Long?
        get() {
            if (!enabled) return null
            
            // If one-off and already run, no next run
            if (periodDays == null && lastRunAt != null) return null
            
            // If never run, run immediately
            if (lastRunAt == null) return Clock.System.now().toEpochMilliseconds()
            
            // Calculate next run based on last run + period
            val period = periodDays ?: return null
            return lastRunAt!! + period.days.inWholeMilliseconds
        }

    /**
     * Checks if this config is due for a run based on current time.
     */
    fun isDue(currentTimeMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        val next = nextRunAt ?: return false
        return next <= currentTimeMs
    }

    fun updateConfig(newUrl: String, newPeriodDays: Int?) {
        url = newUrl
        periodDays = newPeriodDays
        updatedAt = Clock.System.now().toEpochMilliseconds()
    }

    fun markAsRun() {
        val now = Clock.System.now().toEpochMilliseconds()
        lastRunAt = now
        updatedAt = now
    }

    fun disable() {
        enabled = false
        updatedAt = Clock.System.now().toEpochMilliseconds()
    }

    fun enable() {
        enabled = true
        updatedAt = Clock.System.now().toEpochMilliseconds()
    }
}
