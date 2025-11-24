package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a webpage source with its URL and markdown content.
 * Used as input for source evaluation and shortlisting.
 */
@Serializable
data class MarkdownSource(
    val url: String,
    val markdown: String
)
