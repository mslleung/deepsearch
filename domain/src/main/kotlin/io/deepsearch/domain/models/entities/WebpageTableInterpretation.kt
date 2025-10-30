package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents an interpreted table on a webpage.
 * The hash is derived from the table element screenshot bytes and HTML to enable caching.
 */
@OptIn(ExperimentalTime::class)
data class WebpageTableInterpretation(
    val tableDataHash: ByteArray, // Hash of screenshot bytes + html
    val markdown: String,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)

