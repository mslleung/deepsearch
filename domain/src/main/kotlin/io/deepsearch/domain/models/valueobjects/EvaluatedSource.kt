package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents an evaluated source with extracted facts and descriptive metadata.
 * 
 * This is the output from individual source evaluation agents (HTML and Markdown)
 * and is used directly by the answer synthesis agent.
 * 
 * @property url The URL of the source
 * @property title Title of the source page (nullable)
 * @property description Meta description of the source (nullable)
 * @property relevantFacts List of facts extracted from the source that are relevant to the query
 * @property contentDate Date extracted from content (nullable if no date found)
 * @property intention Describes the purpose of the webpage (e.g., "Official pricing page showing subscription tiers")
 * @property relevantImageIds List of image IDs (format: "img-xxx") deemed relevant for this source
 * @property isPreview True if this source was evaluated from HTML preview (partial content),
 *           false if from full markdown. Preview sources may have incomplete facts.
 */
@Serializable
data class EvaluatedSource(
    val url: String,
    val title: String?,
    val description: String?,
    val relevantFacts: List<RelevantFact>,
    val contentDate: String?,
    val intention: String,
    val relevantImageIds: List<String> = emptyList(),
    val isPreview: Boolean = false
)
