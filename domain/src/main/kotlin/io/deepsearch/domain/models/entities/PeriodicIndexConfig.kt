package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.OcrLanguage
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
        val ALLOWED_DAYS: Set<Int?> = entries.map { it.days }.toSet()

        fun fromDays(days: Int?): PeriodicIndexPeriod {
            return entries.find { it.days == days } ?: ONE_OFF
        }

        fun isValidPeriodDays(days: Int?): Boolean = days in ALLOWED_DAYS

        fun requireValidPeriodDays(days: Int?) {
            require(isValidPeriodDays(days)) {
                "Invalid period days: $days. Allowed values are: ${ALLOWED_DAYS.filterNotNull().sorted().joinToString(", ")} (or null for one-off)"
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
class PeriodicIndexConfig(
    val id: Long? = null,
    val userId: UserId,
    var url: String,
    var sitemapUrl: String? = null,
    periodDays: Int? = null, // null means one-off
    maxUrlCount: Int = DEFAULT_MAX_URL_COUNT,
    var enabled: Boolean = true,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    var updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    var lastRunAt: Long? = null,
    var version: Long = 0,
    /**
     * Language filter pattern for URL filtering during crawling.
     * Can be either:
     * - Path pattern: `/en-us/` - matches URLs with this path segment
     * - Query pattern: `?lang=en` - matches URLs with this query parameter
     * Null means no language filtering (crawl all languages).
     */
    var languagePattern: String? = null,
    /**
     * OCR language for Tesseract text extraction from images.
     * Defaults to English.
     */
    var ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT
) {
    var periodDays: Int? = periodDays
        private set

    var maxUrlCount: Int = maxUrlCount
        private set

    init {
        PeriodicIndexPeriod.requireValidPeriodDays(periodDays)
        require(maxUrlCount in MIN_MAX_URL_COUNT..MAX_MAX_URL_COUNT) {
            "maxUrlCount must be between $MIN_MAX_URL_COUNT and $MAX_MAX_URL_COUNT"
        }
    }

    companion object {
        const val DEFAULT_MAX_URL_COUNT = 100
        const val MIN_MAX_URL_COUNT = 1
        const val MAX_MAX_URL_COUNT = 1000
    }
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

    fun updateConfig(
        newUrl: String, 
        newSitemapUrl: String?, 
        newPeriodDays: Int?, 
        newMaxUrlCount: Int = maxUrlCount,
        newLanguagePattern: String? = languagePattern,
        newOcrLanguage: OcrLanguage = ocrLanguage
    ) {
        PeriodicIndexPeriod.requireValidPeriodDays(newPeriodDays)
        require(newMaxUrlCount in MIN_MAX_URL_COUNT..MAX_MAX_URL_COUNT) {
            "maxUrlCount must be between $MIN_MAX_URL_COUNT and $MAX_MAX_URL_COUNT"
        }
        url = newUrl
        sitemapUrl = newSitemapUrl
        periodDays = newPeriodDays
        maxUrlCount = newMaxUrlCount
        languagePattern = newLanguagePattern
        ocrLanguage = newOcrLanguage
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
