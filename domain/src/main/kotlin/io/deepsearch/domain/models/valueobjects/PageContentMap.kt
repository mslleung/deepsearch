package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Catalog of interactive elements and section headings on a webpage.
 * Populated during visual-order indexing, used as hints for agentic in-page navigation.
 */
@Serializable
data class PageContentMap(
    val interactiveElements: List<InteractiveElement>,
    val sectionHeadings: List<SectionHeading>
)

@Serializable
data class InteractiveElement(
    val dataDsId: String,
    val label: String,
    val type: InteractiveElementType,
    val ariaExpanded: String? = null
)

@Serializable
enum class InteractiveElementType {
    ACCORDION,
    TAB,
    DETAILS,
    TOGGLE
}

@Serializable
data class SectionHeading(
    val dataDsId: String,
    val text: String,
    val level: Int
)
