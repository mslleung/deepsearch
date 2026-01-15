package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Cached webpage image with extracted text.
 * 
 * Images are stored in GCS and referenced by [gcsPath].
 * The actual image bytes are fetched from GCS when needed (via signed URLs).
 */
@OptIn(ExperimentalTime::class)
data class WebpageImage(
    val imageBytesHash: ByteArray, // SHA-256 hash of the image content (used as unique key)
    val gcsPath: String, // GCS storage path (e.g., "images/abc123")
    val mimeType: String, // Image MIME type (e.g., "image/webp", "image/png")
    val extractedText: String?, // null if the image contains no text or cannot be extracted
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WebpageImage
        return imageBytesHash.contentEquals(other.imageBytesHash)
    }

    override fun hashCode(): Int = imageBytesHash.contentHashCode()
}
