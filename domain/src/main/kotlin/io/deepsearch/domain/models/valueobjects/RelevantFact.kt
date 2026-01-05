package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * A fact extracted from a source.
 * 
 * Used by both the full markdown path and preview path for answer synthesis.
 * Facts from tables in the preview path are filtered out before reaching this model.
 * 
 * @property fact The extracted fact as a complete, standalone statement
 */
@Serializable
data class RelevantFact(
    val fact: String
)
