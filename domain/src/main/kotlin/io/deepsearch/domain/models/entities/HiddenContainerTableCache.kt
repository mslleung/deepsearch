package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Cached result of hidden container analysis (tables and mobile layouts).
 * 
 * Hidden containers (accordion panels, tabs, collapsed sections, etc.) contain HTML that
 * may include table structures and mobile layouts. This cache stores the detection result keyed by
 * a hash of the structural HTML (with data-ds-id attributes stripped).
 * 
 * The cached result includes CSS selectors that can be used to find elements
 * in subsequent page loads, even when data-ds-id values change.
 */
@OptIn(ExperimentalTime::class)
data class HiddenContainerTableCache(
    /** SHA-256 hash of the structural HTML (with data-ds-id stripped) */
    val structuralHtmlHash: ByteArray,
    
    /** Whether this container contains any tables */
    val hasTables: Boolean,
    
    /** 
     * JSON array of detected tables. Each entry contains:
     * - cssSelector: CSS selector to find the table element
     * - specialConsideration: Optional notes about non-standard structure
     * 
     * Empty array if hasTables is false.
     */
    val tablesJson: String,
    
    /** Whether this container contains any mobile layouts */
    val hasMobileLayouts: Boolean = false,
    
    /**
     * JSON array of detected mobile layouts. Each entry contains:
     * - cssSelector: CSS selector to find the element
     * - description: Description (e.g., "Mobile navigation menu")
     * 
     * Empty array if hasMobileLayouts is false.
     */
    val mobileLayoutsJson: String = "[]",
    
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HiddenContainerTableCache
        return structuralHtmlHash.contentEquals(other.structuralHtmlHash)
    }

    override fun hashCode(): Int {
        return structuralHtmlHash.contentHashCode()
    }
}

/**
 * Represents a single table detected within a hidden container.
 * Used for JSON serialization in HiddenContainerTableCache.tablesJson
 */
@kotlinx.serialization.Serializable
data class CachedHiddenTable(
    /** CSS selector to locate this table element (stable across page loads) */
    val cssSelector: String,
    /** Optional notes about non-standard table structure that may affect interpretation */
    val specialConsideration: String? = null
)

/**
 * Represents a single mobile layout detected within a hidden container.
 * Used for JSON serialization in HiddenContainerTableCache.mobileLayoutsJson
 */
@kotlinx.serialization.Serializable
data class CachedHiddenMobileLayout(
    /** CSS selector to locate this element (stable across page loads) */
    val cssSelector: String,
    /** Description (e.g., "Mobile navigation menu", "Hamburger menu content") */
    val description: String
)
