package io.deepsearch.domain.models.entities

data class WebpageIcon(
    val imageBytesHash: ByteArray,
    val label: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)