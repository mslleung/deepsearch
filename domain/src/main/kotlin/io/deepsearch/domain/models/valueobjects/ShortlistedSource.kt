package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a shortlisted source for answering a query.
 * Contains the source content and classification metadata about its type, temporal state, and answer quality.
 *
 * @property isPreview true if using simple text extraction (no tables/images), false for full markdown
 * @property relevantImageIds list of image IDs (format: "img-xxx") deemed relevant for this source
 */
@Serializable
data class ShortlistedSource(
    val url: String,
    val markdown: String,
    val sourceClassification: SourceType,
    val contentDate: String?,       // Date extracted from content (nullable if no date found)
    val answerType: AnswerType,
    val relevanceJustification: String,  // Brief reason for inclusion in shortlist
    val isPreview: Boolean = false,      // Track if this shortlisted source uses preview content
    val relevantImageIds: List<String> = emptyList()  // Image IDs relevant for answering the query
)

