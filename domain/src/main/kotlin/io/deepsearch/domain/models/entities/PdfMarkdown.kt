package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class PdfMarkdown(
    val pdfHash: String, // SHA-256 hash of PDF bytes
    val markdown: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)

