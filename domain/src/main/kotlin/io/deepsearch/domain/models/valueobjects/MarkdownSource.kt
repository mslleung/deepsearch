package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a webpage source with its URL, metadata, and full markdown content.
 * Used as input for source evaluation.
 */
@Serializable
data class MarkdownSource(
    val url: String,
    val title: String?,
    val description: String?,
    val markdown: String
)
