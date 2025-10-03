package io.deepsearch.domain.models.entities

/**
 * Represents an interpreted table on a webpage.
 * The hash is derived from the table element screenshot bytes and HTML to enable caching.
 */
data class WebpageTableInterpretation(
    val tableDataHash: ByteArray, // Hash of screenshot bytes + html
    val markdown: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

