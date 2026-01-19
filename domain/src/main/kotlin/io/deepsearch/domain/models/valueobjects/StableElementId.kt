package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

/**
 * Type of stable element ID.
 */
enum class StableIdType {
    /** Structural/semantic elements: div, section, table, tr, td, ul, li, header, footer, nav, etc. */
    ELEMENT,
    /** Icon elements: i, svg */
    ICON,
    /** Image elements: img, picture, background-image elements */
    IMAGE
}

/**
 * A stable, unique identifier for a DOM element.
 * 
 * These IDs are injected into the DOM via data-ds-id attributes by the browser's
 * injectStableIds operation and remain stable throughout the extraction pipeline.
 * 
 * Format: ds-{type}-{id} where type is "element", "icon", or "image" and id is an integer.
 */
@Serializable
data class StableElementId(
    val type: StableIdType,
    val id: Int
) {
    /**
     * The string representation of this ID as it appears in the data-ds-id attribute.
     * Example: "ds-element-5", "ds-icon-12", "ds-image-3"
     */
    val stringValue: String get() = "ds-${type.name.lowercase()}-$id"

    /**
     * A CSS selector that uniquely identifies this element by its data-ds-id.
     * Example: [data-ds-id="ds-element-5"]
     */
    val cssSelector: String get() = "[data-ds-id=\"$stringValue\"]"

    companion object {
        private val PATTERN = Regex("ds-(element|icon|image)-(\\d+)")

        /**
         * Parse a stable element ID from its string representation.
         * 
         * @param value The string value (e.g., "ds-element-5", "ds-icon-12")
         * @return The parsed StableElementId, or null if the format is invalid
         */
        fun parse(value: String): StableElementId? {
            val match = PATTERN.matchEntire(value) ?: return null
            val type = StableIdType.valueOf(match.groupValues[1].uppercase())
            val id = match.groupValues[2].toInt()
            return StableElementId(type, id)
        }

        /**
         * Create a stable element ID from a string value.
         * 
         * @param value The string value (e.g., "ds-element-5", "ds-icon-12")
         * @return The parsed StableElementId
         * @throws IllegalArgumentException if the format is invalid
         */
        fun of(value: String): StableElementId {
            return parse(value) ?: throw IllegalArgumentException("Invalid stable element ID format: $value")
        }
    }
}
