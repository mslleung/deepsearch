package io.deepsearch.domain.models.valueobjects

import io.deepsearch.domain.constants.ImageMimeType

/**
 * Rendered web icon bitmap and metadata used for interpretation and caching.
 *
 * selector: a best-effort selector hint to locate or identify the element (not guaranteed stable).
 * imageBytesHash: SHA-256 hex of bytes for deduplication.
 * bytes: raw image bytes (typically JPEG).
 * mimeType: image mime type, defaults to JPEG.
 */
data class WebIconBitmap(
    val selector: String,
    val imageBytesHash: String,
    val bytes: ByteArray,
    val mimeType: ImageMimeType = ImageMimeType.JPEG
)