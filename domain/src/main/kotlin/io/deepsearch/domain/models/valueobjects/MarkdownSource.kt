package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a webpage source with its URL, metadata, and full markdown content.
 * Used as input for source evaluation by the main path.
 *
 * Note: Preview content (HTML) is handled separately via UrlContentResult.HtmlPreview
 * and goes through the preview agents path.
 */
@Serializable
data class MarkdownSource(
    val url: String,
    val title: String?,
    val description: String?,
    val markdown: String
)
