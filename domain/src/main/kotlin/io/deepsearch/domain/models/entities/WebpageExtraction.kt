package io.deepsearch.domain.models.entities

/**
 * Represents extracted webpage content cached in the database.
 * The hash is derived from the webpage HTML to enable deduplication of extraction results.
 */
data class WebpageExtraction(
    val webpageHtmlHash: ByteArray,
    val extractedMarkdown: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

