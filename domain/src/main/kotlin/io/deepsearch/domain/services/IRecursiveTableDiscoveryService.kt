package io.deepsearch.domain.services

import io.deepsearch.domain.browser.IBrowserPage

/**
 * Result of recursive table discovery.
 */
data class DiscoveredTable(
    /** The local element ID (data-ds-local) within the container HTML */
    val localElementId: String,
    /** Stable CSS selector to find the container in the original snapshot HTML */
    val containerLocator: String,
    /** Container HTML with data-ds-local attributes (for element lookup) */
    val containerHtml: String,
    /** Grid detection result */
    val gridResult: TableGridResult,
    /** Depth in the DOM tree where this table was found */
    val depth: Int,
    /** Bounding boxes of elements within this table */
    val elementBoundingBoxes: Map<String, TableDetectionBoundingBox>
)

/**
 * Service for recursively discovering tables within hidden containers.
 * 
 * This service traverses the DOM tree (parsed from HTML) and performs spatial analysis
 * at each level to find table structures. It uses the bounding boxes captured from the
 * browser to detect grid patterns without relying on HTML table semantics.
 * 
 * Key features:
 * - Recursive DOM traversal using Jsoup
 * - Spatial grid detection at each level
 * - Automatic deduplication of nested/overlapping tables
 * - Prefers more specific (deeper) tables over parent containers
 */
interface IRecursiveTableDiscoveryService {
    /**
     * Discover tables within a hidden container by recursively traversing the DOM.
     * 
     * @param containerHtml HTML content of the hidden container
     * @param boundingBoxes Map of element ID to bounding box for all elements in the container
     * @return List of discovered tables, deduplicated to avoid overlapping results
     */
    fun discoverTables(
        containerHtml: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): List<DiscoveredTable>
    
    /**
     * Discover tables from hidden container data captured by the browser.
     * 
     * Each container's HTML (with local IDs) is used directly for DOM traversal,
     * avoiding dependence on global data-ds-id which may be unstable after React re-renders.
     * 
     * @param hiddenContainerData Data from captureHiddenContainerBoundingBoxes()
     * @return List of discovered tables across all hidden containers, deduplicated
     */
    fun discoverTablesFromHiddenContainers(
        hiddenContainerData: IBrowserPage.HiddenContainerBoundingBoxes
    ): List<DiscoveredTable>
}
