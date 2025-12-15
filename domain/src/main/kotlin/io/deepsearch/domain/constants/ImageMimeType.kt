package io.deepsearch.domain.constants

/**
 * Supported image MIME types.
 * These are the formats supported by Gemini API for vision tasks.
 */
enum class ImageMimeType(val value: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    HEIC("image/heic"),
    HEIF("image/heif");
    
    companion object {
        fun fromValue(value: String): ImageMimeType {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown MIME type: $value")
        }
    }
}