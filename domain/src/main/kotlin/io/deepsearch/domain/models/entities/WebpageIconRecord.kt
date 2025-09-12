package io.deepsearch.domain.models.entities

import io.deepsearch.domain.constants.ImageMimeType

data class WebpageIconRecord(
    val selector: String,
    val imageBytesHash: String,
    val mimeType: ImageMimeType,
    val jpegBytes: ByteArray,
    val label: String,
    val confidence: Double,
    val hints: List<String> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)