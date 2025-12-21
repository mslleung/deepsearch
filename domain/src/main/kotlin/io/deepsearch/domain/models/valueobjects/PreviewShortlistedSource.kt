package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents a shortlisted source from the preview path.
 * Contains extracted facts with high confidence for early answer synthesis.
 * 
 * The preview path is very restrictive - only sources with unambiguous prose content
 * and no tables/images/icons are shortlisted.
 * 
 * @property url The URL of the source
 * @property title The page title
 * @property extractedFacts Absolute facts extracted from prose content
 * @property confidence Confidence score (0.0-1.0) in the extracted facts
 * @property relevanceJustification Brief reason for inclusion in shortlist
 */
@Serializable
data class PreviewShortlistedSource(
    val url: String,
    val title: String?,
    val extractedFacts: List<String>,
    val confidence: Float,
    val relevanceJustification: String
)
