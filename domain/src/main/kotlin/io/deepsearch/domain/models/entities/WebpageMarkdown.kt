package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class WebpageMarkdown(
    val url: String, // normalized URL
    val title: String?,
    val description: String?,
    val markdown: String?,
    val html: String?,
    val httpStatus: Int?,
    val httpReason: String?,
    val mimeType: String?,
    val embedding: List<Float>? = null, // 1536-dimensional embedding vector for semantic search
    val isPreview: Boolean = false, // true if content is from simple text extraction (no LLM processing)
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)

