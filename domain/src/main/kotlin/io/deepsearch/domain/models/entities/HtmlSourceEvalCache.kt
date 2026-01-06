package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Cache entry for HTML source evaluation results.
 * 
 * The cache key is a SHA-256 hash of (query + cleanedHtml), stored as contentHash.
 * This allows reusing LLM evaluation results when the same content is evaluated
 * for the same query (e.g., across different search sessions).
 * 
 * @property contentHash SHA-256 hash of query + cleanedHtml
 * @property evaluatedSourceJson Serialized EvaluatedSource JSON, or null if not relevant
 * @property promptTokens Number of prompt tokens used (for metrics even on cache hit)
 * @property outputTokens Number of output tokens used
 * @property totalTokens Total tokens used
 */
@OptIn(ExperimentalTime::class)
data class HtmlSourceEvalCache(
    val contentHash: ByteArray,
    val evaluatedSourceJson: String?,
    val promptTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val version: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HtmlSourceEvalCache) return false
        return contentHash.contentEquals(other.contentHash)
    }

    override fun hashCode(): Int = contentHash.contentHashCode()
}

