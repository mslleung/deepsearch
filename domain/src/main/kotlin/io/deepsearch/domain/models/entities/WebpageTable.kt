package io.deepsearch.domain.models.entities

/**
 * Represents a table identified on a webpage.
 * The hash is derived from the webpage HTML to enable caching of table identification results.
 */
data class WebpageTable(
    val webpageHtmlHash: ByteArray,
    val tables: String, // JSON serialized list of TableIdentification
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

