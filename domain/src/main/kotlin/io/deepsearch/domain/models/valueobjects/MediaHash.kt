package io.deepsearch.domain.models.valueobjects

/**
 * Typed wrapper for base64-encoded hash of media (icons/images).
 */
@JvmInline
value class MediaHash(val value: String) {
    init {
        require(value.isNotBlank()) { "Media hash must not be blank" }
    }
}

