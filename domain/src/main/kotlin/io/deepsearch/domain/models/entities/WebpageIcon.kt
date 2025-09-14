package io.deepsearch.domain.models.entities

data class WebpageIcon(
    val imageBytesHash: ByteArray,
    val label: String?, // null if the icon is blank or cannot be interpreted, this prevents repeated sending it to the LLM
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)