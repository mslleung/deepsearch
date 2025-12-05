package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class WebpageImage(
    val imageBytesHash: ByteArray,
    val imageBytes: ByteArray, // Raw image bytes for returning in responses
    val mimeType: String, // Image MIME type (e.g., "image/webp", "image/png")
    val extractedText: String?, // null if the image contains no text or cannot be extracted
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)
