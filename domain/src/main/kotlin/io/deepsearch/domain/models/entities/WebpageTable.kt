package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a table identified on a webpage.
 * The hash is derived from the webpage HTML to enable caching of table identification results.
 */
@OptIn(ExperimentalTime::class)
data class WebpageTable(
    val webpageHtmlHash: ByteArray,
    val tables: String, // JSON serialized list of TableIdentification
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 1
)

