package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a linkage between a webpage URL and an image.
 * Tracks which images are present on which URLs and whether they are still active
 * (found in the latest extraction of that URL).
 */
@OptIn(ExperimentalTime::class)
data class WebpageImageLinkage(
    val url: String,
    val imageBytesHash: ByteArray,
    val isActive: Boolean = true,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WebpageImageLinkage

        if (url != other.url) return false
        if (!imageBytesHash.contentEquals(other.imageBytesHash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + imageBytesHash.contentHashCode()
        return result
    }
}


