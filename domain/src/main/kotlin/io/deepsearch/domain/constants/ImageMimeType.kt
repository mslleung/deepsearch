package io.deepsearch.domain.constants

/**
 * Supported image MIME types.
 */
enum class ImageMimeType(val value: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp");
    
    companion object {
        fun fromValue(value: String): ImageMimeType {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown MIME type: $value")
        }
    }
}