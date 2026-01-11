package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Cache entry for PDF source evaluation results.
 * 
 * The cache key is a SHA-256 hash of (query + extractedText), stored as contentHash.
 * This allows reusing LLM evaluation results when the same PDF content is evaluated
 * for the same query (e.g., across different search sessions).
 * 
 * @property contentHash SHA-256 hash of query + extractedText
 * @property evaluatedSourceJson Serialized EvaluatedSource JSON, or null if not relevant
 */
@OptIn(ExperimentalTime::class)
data class PdfSourceEvalCache(
    val contentHash: ByteArray,
    val evaluatedSourceJson: String?,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val version: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfSourceEvalCache) return false
        return contentHash.contentEquals(other.contentHash)
    }

    override fun hashCode(): Int = contentHash.contentHashCode()
}
