package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * A fact extracted from a source with metadata about its source classification.
 * 
 * Used by both the full markdown path and preview path for answer synthesis.
 * Facts from tables in the preview path are filtered out before reaching this model.
 * 
 * @property fact The extracted fact as a complete, standalone statement
 * @property sourceClassification Classification of the source (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 */
@Serializable
data class RelevantFact(
    val fact: String,
    val sourceClassification: SourceClassification
)

/**
 * Classification of a source based on its authority and freshness.
 */
@Serializable
enum class SourceClassification {
    /** Official main pages (e.g., /pricing, /home, /features) intended to reflect current state */
    OFFICIAL_LIVING_DOC,
    /** Dated company updates (e.g., /blog, /press, /news) */
    OFFICIAL_SNAPSHOT,
    /** External reviews, news sites, forums, UGC, etc. */
    OTHERS
}

