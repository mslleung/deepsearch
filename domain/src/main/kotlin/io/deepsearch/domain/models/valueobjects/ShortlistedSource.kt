package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a shortlisted source for answering a query.
 * Contains extracted relevant facts and classification metadata about the source.
 *
 * Used by both the full markdown path and preview path for answer synthesis.
 * The answer synthesis agents receive facts instead of full source content.
 *
 * @property url The URL of the source
 * @property relevantFacts List of facts extracted from the source that are relevant to the query
 * @property sourceClassification Classification of the source type
 * @property contentDate Date extracted from content (nullable if no date found)
 * @property answerType How directly the source answers the query
 * @property relevanceJustification Brief reason for inclusion in shortlist
 * @property relevantImageIds List of image IDs (format: "img-xxx") deemed relevant for this source
 */
@Serializable
data class ShortlistedSource(
    val url: String,
    val relevantFacts: List<RelevantFact>,
    val sourceClassification: SourceType,
    val contentDate: String?,
    val answerType: AnswerType,
    val relevanceJustification: String,
    val relevantImageIds: List<String> = emptyList()
)

