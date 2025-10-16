package io.deepsearch.domain.models.entities

data class WebpageMarkdown(
    val url: String, // normalized URL
    val markdown: String?,
    val html: String?,
    val httpStatus: Int?,
    val httpReason: String?,
    val mimeType: String?,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

