package io.deepsearch.domain.models.entities

/**
 * Represents a cached query answer.
 * The hash is derived from the query, URL, screenshot, and HTML to enable caching of query answering results.
 */
data class QueryAnswer(
    val queryHash: ByteArray,
    val answer: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
