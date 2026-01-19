package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Cached result of vision-based detection (semantic elements + tables).
 * 
 * This caches the raw vision LLM response which contains bounding boxes in
 * normalized [0, 1000] coordinates. The bounding boxes can be remapped to
 * DOM elements on subsequent page loads using IoU matching.
 * 
 * Cache key is a hash of:
 * - Screenshot bytes
 * - Structural HTML (with data-ds-id stripped)
 */
@OptIn(ExperimentalTime::class)
data class VisionDetectionCache(
    /** SHA-256 hash of screenshot bytes + structural HTML */
    val contentHash: ByteArray,
    
    /**
     * Raw vision LLM response JSON containing:
     * - Semantic elements (header, footer, nav, breadcrumb, cookieBanner, popups) with bounding boxes
     * - Tables with bounding boxes and labels
     * 
     * Bounding boxes are in normalized [0, 1000] coordinates.
     */
    val visionResponseJson: String,
    
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VisionDetectionCache
        return contentHash.contentEquals(other.contentHash)
    }

    override fun hashCode(): Int {
        return contentHash.contentHashCode()
    }
}
