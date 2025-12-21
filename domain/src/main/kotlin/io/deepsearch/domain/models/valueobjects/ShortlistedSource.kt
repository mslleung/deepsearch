package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a shortlisted source for answering a query.
 * Contains the source content and classification metadata about its type, temporal state, and answer quality.
 *
 * Note: Preview content (HTML) is handled separately via the preview agents path.
 *
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
    val relevantImageIds: List<String> = emptyList()  // Image IDs relevant for answering the query
)

