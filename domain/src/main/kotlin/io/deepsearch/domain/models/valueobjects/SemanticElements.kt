package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Represents an identified semantic element on a webpage.
 * The type is determined by which field it's stored in within SemanticElements.
 */
@Serializable
data class IdentifiedElement(
    /** Stable data-ds-id value for element reference (e.g., "ds-element-5"). */
    val dataId: String,
    /** Human-readable note about why this element was identified. */
    val note: String
) {
    /** CSS selector derived from the dataId for easy element lookup. */
    val cssSelector: String get() = "[data-ds-id=\"$dataId\"]"
}

/**
 * Structured collection of all semantic elements on a webpage.
 * Each field represents a specific type of semantic element.
 * Single elements are nullable, while lists default to empty.
 */
@Serializable
data class SemanticElements(
    val header: IdentifiedElement? = null,
    val footer: IdentifiedElement? = null,
    val navSidebar: IdentifiedElement? = null,
    val breadcrumb: IdentifiedElement? = null,
    val cookieBanner: IdentifiedElement? = null,
    val adBanners: List<IdentifiedElement> = emptyList(),
    val popups: List<IdentifiedElement> = emptyList()
)
