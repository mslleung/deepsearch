package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents an evaluated source with extracted facts and classification metadata.
 * 
 * This is the output from individual source evaluation agents (HTML and Markdown)
 * and is used directly by the answer synthesis agent.
 * 
 * @property url The URL of the source
 * @property title Title of the source page (nullable)
 * @property description Meta description of the source (nullable)
 * @property relevantFacts List of facts extracted from the source that are relevant to the query
 * @property sourceClassification Classification of the source type
 * @property contentDate Date extracted from content (nullable if no date found)
 * @property relevance How relevant the source is to the query (CANONICAL, PARTIAL_MENTION, NOT_RELEVANT)
 * @property relevanceReasoning Brief reason for inclusion
 * @property relevantImageIds List of image IDs (format: "img-xxx") deemed relevant for this source
 */
@Serializable
data class EvaluatedSource(
    val url: String,
    val title: String?,
    val description: String?,
    val relevantFacts: List<RelevantFact>,
    val sourceClassification: SourceType,
    val contentDate: String?,
    val relevance: SourceRelevance,
    val relevanceReasoning: String,
    val relevantImageIds: List<String> = emptyList()
)
