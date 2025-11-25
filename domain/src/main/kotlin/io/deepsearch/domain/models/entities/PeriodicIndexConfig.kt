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
    var nextRunAt: Long? = null,
    var version: Long = 0
) {
    init {
        // If it's a new config and enabled, calculate next run immediately if not set
        if (enabled && nextRunAt == null && lastRunAt == null) {
            nextRunAt = Clock.System.now().toEpochMilliseconds()
        }
    }

    fun updateConfig(newUrl: String, newPeriodDays: Int?) {
        url = newUrl
        periodDays = newPeriodDays
        updatedAt = Clock.System.now().toEpochMilliseconds()
        
        // Recalculate next run time
        calculateNextRunTime()
    }

    fun markAsRun() {
        val now = Clock.System.now().toEpochMilliseconds()
        lastRunAt = now
        updatedAt = now
        calculateNextRunTime()
    }

    fun disable() {
        enabled = false
        nextRunAt = null
        updatedAt = Clock.System.now().toEpochMilliseconds()
    }

    fun enable() {
        enabled = true
        updatedAt = Clock.System.now().toEpochMilliseconds()
        calculateNextRunTime()
    }

    private fun calculateNextRunTime() {
        if (!enabled) {
            nextRunAt = null
            return
        }

        // If one-off and already run, no next run
        if (periodDays == null && lastRunAt != null) {
            nextRunAt = null
            return
        }

        // If never run, run immediately (or keep existing schedule if in future)
        if (lastRunAt == null) {
             if (nextRunAt == null) {
                 nextRunAt = Clock.System.now().toEpochMilliseconds()
             }
             return
        }

        // Calculate next run based on last run + period
        val period = periodDays ?: 0
        if (period > 0) {
            // Use Instant for date arithmetic if needed, but simple ms addition is okay for rough scheduling
            // Using kotlinx.datetime would be better but staying consistent with Long timestamps here
            nextRunAt = lastRunAt!! + period.days.inWholeMilliseconds
        } else {
            // One-off that just finished
            nextRunAt = null
        }
    }
}

