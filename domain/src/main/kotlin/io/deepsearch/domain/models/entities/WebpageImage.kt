package io.deepsearch.domain.models.entities

data class WebpageImage(
    val imageBytesHash: ByteArray,
    val extractedText: String?, // null if the image contains no text or cannot be extracted
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
