package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class WebpageMarkdown(
    val url: String, // normalized URL
    val markdown: String?,
    val html: String?,
    val httpStatus: Int?,
    val httpReason: String?,
    val mimeType: String?,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)

