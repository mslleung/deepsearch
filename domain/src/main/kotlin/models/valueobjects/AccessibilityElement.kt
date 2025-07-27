package io.deepsearch.domain.models.valueobjects

/**
 * Represents an accessibility element identified during website scanning
 */
data class AccessibilityElement(
    val ruleId: String,
    val impact: String,
    val description: String,
    val helpUrl: String,
    val tags: List<String>,
    val target: String,
    val html: String,
    val violationType: AccessibilityViolationType
)

enum class AccessibilityViolationType {
    VIOLATION,
    INCOMPLETE,
    PASS
}

/**
 * Container for accessibility scan results
 */
data class AccessibilityScanResult(
    val url: String,
    val violations: List<AccessibilityElement>,
    val passes: List<AccessibilityElement>,
    val incomplete: List<AccessibilityElement>,
    val scanTimestamp: Long = System.currentTimeMillis()
) 