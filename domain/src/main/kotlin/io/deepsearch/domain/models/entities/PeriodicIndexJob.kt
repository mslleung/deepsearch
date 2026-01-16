package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.OcrLanguage
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
    var version: Long = 0,
    /**
     * Language filter pattern for URL filtering during crawling.
     * Can be either:
     * - Path pattern: `/en-us/` - matches URLs with this path segment
     * - Query pattern: `?lang=en` - matches URLs with this query parameter
     * Null means no language filtering (crawl all languages).
     */
    val languagePattern: String? = null,
    /**
     * OCR language for Tesseract text extraction from images.
     * Defaults to English.
     */
    val ocrLanguage: OcrLanguage = OcrLanguage.DEFAULT,
    /**
     * Reason for job abortion (only set when state is ABORTED).
     * This is different from STOPPED (user-initiated) - ABORTED means the crawler
     * detected that the site is blocking it and continuing would be futile.
     */
    var abortReason: String? = null
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
        if (state == PeriodicIndexJobState.COMPLETED) return
        state = PeriodicIndexJobState.COMPLETED
        updatedAt = Clock.System.now()
    }

    /**
     * Mark job as aborted due to site-wide blocking detection.
     * 
     * @param reason The reason for abortion (e.g., "Cloudflare protection detected")
     */
    fun markAborted(reason: String) {
        if (state == PeriodicIndexJobState.IN_PROGRESS) {
            state = PeriodicIndexJobState.ABORTED
            abortReason = reason
            updatedAt = Clock.System.now()
        }
    }
}

enum class PeriodicIndexJobState {
    IN_PROGRESS,
    COMPLETED,
    STOPPED,
    /** Job was aborted due to site-wide blocking detection (Cloudflare, CAPTCHA, etc.) */
    ABORTED
}

