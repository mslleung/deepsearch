package io.deepsearch.domain.models.valueobjects

/**
 * Priority classification for fulfillment requirements.
 * Used by QueryBreakdownAgent to structurally enforce requirement classification.
 */
enum class RequirementPriority(val displayPrefix: String) {
    /** Directly answers what the user explicitly asked */
    PRIMARY("[PRIMARY]"),

    /** Related context that enhances the answer but wasn't explicitly requested */
    SECONDARY("[SECONDARY]");

    fun formatRequirement(text: String): String = "$displayPrefix $text"
}
