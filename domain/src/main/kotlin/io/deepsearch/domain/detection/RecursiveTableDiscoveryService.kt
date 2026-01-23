package io.deepsearch.domain.detection

import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.services.DiscoveredTable
import io.deepsearch.domain.services.IRecursiveTableDiscoveryService
import io.deepsearch.domain.services.ITableGridDetectorService
import io.deepsearch.domain.services.TableDetectionBoundingBox
import io.deepsearch.domain.services.TableGridResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Configuration for recursive table discovery.
 */
data class TableDiscoveryConfig(
    /** Minimum number of leaf elements to consider for table detection */
    val minLeafElements: Int = 6,
    /** Minimum rows for a valid table */
    val minRows: Int = 2,
    /** Minimum columns for a valid table */
    val minCols: Int = 2,
    /** Minimum confidence threshold */
    val minConfidence: Double = 0.5,
    /** Minimum container height in pixels */
    val minHeight: Double = 100.0,
    /** Minimum container width in pixels */
    val minWidth: Double = 200.0,
    /** Maximum recursion depth */
    val maxDepth: Int = 15
)

/**
 * Implementation of recursive table discovery using DOM traversal and spatial analysis.
 */
class RecursiveTableDiscoveryService(
    private val tableGridDetectorService: ITableGridDetectorService,
    private val config: TableDiscoveryConfig = TableDiscoveryConfig()
) : IRecursiveTableDiscoveryService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun discoverTables(
        containerHtml: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>
    ): List<DiscoveredTable> {
        val doc = Jsoup.parse(containerHtml)
        val body = doc.body()
        
        // Convert bounding boxes to detection format
        val detectionBoxes = boundingBoxes.mapValues { (_, box) ->
            TableDetectionBoundingBox(
                left = box.left,
                top = box.top,
                right = box.right,
                bottom = box.bottom
            )
        }
        
        // Find the root container element (first element with data-ds-id)
        val rootElement = body.selectFirst("[data-ds-id]") ?: return emptyList()
        
        // Recursively discover tables
        val discovered = discoverTablesRecursively(rootElement, detectionBoxes, 0)
        
        // Deduplicate overlapping tables
        return deduplicateTables(discovered)
    }

    override fun discoverTablesFromHiddenContainers(
        hiddenContainerData: IBrowserPage.HiddenContainerBoundingBoxes,
        fullPageHtml: String
    ): List<DiscoveredTable> {
        val doc = Jsoup.parse(fullPageHtml)
        val allDiscovered = mutableListOf<DiscoveredTable>()
        
        logger.debug("Processing {} hidden containers", hiddenContainerData.hiddenContainers.size)
        
        for (container in hiddenContainerData.hiddenContainers) {
            logger.debug("Processing container {} with {} elements", container.containerId, container.elements.size)
            
            // Find the container element in the full page HTML
            val containerElement = doc.selectFirst("[data-ds-id=\"${container.containerId}\"]")
            if (containerElement == null) {
                logger.debug("Container element not found in HTML: {}", container.containerId)
                continue
            }
            
            logger.debug("Found container element: {}, children: {}", containerElement.tagName(), containerElement.children().size)
            
            // Convert bounding boxes to detection format
            val detectionBoxes = container.elements.mapValues { (_, box) ->
                TableDetectionBoundingBox(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom
                )
            }
            
            logger.debug("Converted {} bounding boxes", detectionBoxes.size)
            
            // Recursively discover tables within this container
            val discovered = discoverTablesRecursively(containerElement, detectionBoxes, 0)
            logger.debug("Discovered {} tables in container {}", discovered.size, container.containerId)
            allDiscovered.addAll(discovered)
        }
        
        logger.debug("Total discovered before dedup: {}", allDiscovered.size)
        
        // Deduplicate across all containers
        return deduplicateTables(allDiscovered)
    }

    /**
     * Recursively traverse the DOM and discover tables at each level.
     * 
     * Uses a HYBRID approach:
     * - DOM traversal for structure (which containers to check)
     * - Spatial containment to find descendant boxes (robust to ID mismatches from React re-renders)
     */
    private fun discoverTablesRecursively(
        element: Element,
        allBoundingBoxes: Map<String, TableDetectionBoundingBox>,
        depth: Int
    ): List<DiscoveredTable> {
        // Depth limit to prevent infinite recursion
        if (depth > config.maxDepth) {
            return emptyList()
        }
        
        val elementId = element.attr("data-ds-id")
        if (elementId.isBlank()) {
            // No ID, recurse into children
            return element.children()
                .flatMap { discoverTablesRecursively(it, allBoundingBoxes, depth + 1) }
        }
        
        // Get this element's bounding box
        val containerBox = allBoundingBoxes[elementId]
        
        // Find descendant boxes using SPATIAL containment (robust to ID mismatches)
        val descendantBoxes = if (containerBox != null) {
            findContainedBoxes(containerBox, allBoundingBoxes, excludeId = elementId)
        } else {
            // No container box, use empty map (will recurse into children)
            emptyMap()
        }
        
        // Debug logging for significant containers
        if (descendantBoxes.size >= 20 || depth <= 2) {
            logger.debug(
                "[depth={}] {} <{}> - descendantBoxes={}, containerBox={}",
                depth, elementId, element.tagName(),
                descendantBoxes.size, containerBox != null
            )
        }
        
        // Not enough elements for table detection
        if (descendantBoxes.size < config.minLeafElements) {
            // Recurse into children
            return element.children()
                .flatMap { discoverTablesRecursively(it, allBoundingBoxes, depth + 1) }
        }
        
        // Check container dimensions
        if (containerBox != null) {
            if (containerBox.height < config.minHeight || containerBox.width < config.minWidth) {
                // Too small, recurse into children
                return element.children()
                    .flatMap { discoverTablesRecursively(it, allBoundingBoxes, depth + 1) }
            }
        }
        
        // Filter to leaf boxes (boxes that don't contain other boxes)
        val leafBoxes = filterToLeafBoxes(descendantBoxes)
        
        if (leafBoxes.size < config.minLeafElements) {
            return element.children()
                .flatMap { discoverTablesRecursively(it, allBoundingBoxes, depth + 1) }
        }
        
        // Run grid detection on leaf boxes
        val gridResult = tableGridDetectorService.detectTable(leafBoxes)
        
        logger.debug(
            "[depth={}] {} grid detection: isTable={}, conf={}, grid={}x{}, reason={}",
            depth, elementId, gridResult.isTable, "%.2f".format(gridResult.confidence),
            gridResult.rowCount, gridResult.colCount, gridResult.reason
        )
        
        if (gridResult.isTable && 
            gridResult.confidence >= config.minConfidence &&
            gridResult.rowCount >= config.minRows &&
            gridResult.colCount >= config.minCols) {
            // Found a table at this level!
            logger.debug(
                "[depth={}] ✅ Found table at {}: {}x{} (confidence={}, boxes={})",
                depth, elementId, gridResult.rowCount, gridResult.colCount,
                "%.2f".format(gridResult.confidence), leafBoxes.size
            )
            
            // Return this table, but also check children for nested tables
            val thisTable = DiscoveredTable(
                elementId = elementId,
                gridResult = gridResult,
                depth = depth,
                elementBoundingBoxes = leafBoxes
            )
            
            // Also recurse into children to find more specific nested tables
            val childTables = element.children()
                .flatMap { discoverTablesRecursively(it, allBoundingBoxes, depth + 1) }
            
            return listOf(thisTable) + childTables
        }
        
        // Not a table at this level, recurse into children
        return element.children()
            .flatMap { discoverTablesRecursively(it, allBoundingBoxes, depth + 1) }
    }

    /**
     * Find all bounding boxes that are spatially contained within the container box.
     * This is robust to ID mismatches from React re-renders.
     */
    private fun findContainedBoxes(
        containerBox: TableDetectionBoundingBox,
        allBoxes: Map<String, TableDetectionBoundingBox>,
        excludeId: String
    ): Map<String, TableDetectionBoundingBox> {
        return allBoxes.filterKeys { it != excludeId }.filter { (_, box) ->
            // Box is contained if it's entirely within the container (with small tolerance)
            val tolerance = 5.0
            box.left >= containerBox.left - tolerance &&
            box.top >= containerBox.top - tolerance &&
            box.right <= containerBox.right + tolerance &&
            box.bottom <= containerBox.bottom + tolerance
        }
    }

    /**
     * Filter to only leaf boxes (boxes that don't contain other boxes).
     */
    private fun filterToLeafBoxes(boxes: Map<String, TableDetectionBoundingBox>): Map<String, TableDetectionBoundingBox> {
        return boxes.filter { (id, box) ->
            // Check if this box contains any other box
            val containsOther = boxes.any { (otherId, otherBox) ->
                otherId != id && isContained(otherBox, box)
            }
            !containsOther
        }
    }

    /**
     * Check if inner box is contained within outer box.
     */
    private fun isContained(inner: TableDetectionBoundingBox, outer: TableDetectionBoundingBox): Boolean {
        val tolerance = 5.0
        return inner.left >= outer.left - tolerance &&
               inner.top >= outer.top - tolerance &&
               inner.right <= outer.right + tolerance &&
               inner.bottom <= outer.bottom + tolerance &&
               // Inner must be significantly smaller to be "contained"
               (inner.width < outer.width * 0.95 || inner.height < outer.height * 0.95)
    }

    /**
     * Deduplicate overlapping tables, preferring more specific (deeper) tables.
     * 
     * When a parent and child both detected as tables, we prefer the child
     * unless the parent has significantly higher confidence.
     */
    private fun deduplicateTables(tables: List<DiscoveredTable>): List<DiscoveredTable> {
        if (tables.size <= 1) return tables
        
        // Group by element ID to handle exact duplicates
        val byId = tables.groupBy { it.elementId }
        val uniqueTables = byId.map { (_, group) ->
            // If multiple entries for same ID, take the one with highest confidence
            group.maxByOrNull { it.gridResult.confidence }!!
        }
        
        // Sort by depth (deeper first) so we process children before parents
        val sortedByDepth = uniqueTables.sortedByDescending { it.depth }
        
        val result = mutableListOf<DiscoveredTable>()
        val excludedIds = mutableSetOf<String>()
        
        for (table in sortedByDepth) {
            if (table.elementId in excludedIds) continue
            
            // Check if this table's elements are a subset of any existing result
            val tableElementIds = table.elementBoundingBoxes.keys
            
            var isSubsetOfExisting = false
            for (existing in result) {
                val existingElementIds = existing.elementBoundingBoxes.keys
                
                // Check overlap: if >80% of this table's elements are in an existing table,
                // this is likely a parent container that we should skip
                val overlap = tableElementIds.intersect(existingElementIds)
                val overlapRatio = overlap.size.toDouble() / tableElementIds.size
                
                if (overlapRatio > 0.8) {
                    // This table significantly overlaps with an existing one
                    // Since we process deeper tables first, the existing one is more specific
                    isSubsetOfExisting = true
                    logger.debug(
                        "Skipping {} (depth={}) - {}% overlap with {} (depth={})",
                        table.elementId, table.depth,
                        "%.0f".format(overlapRatio * 100),
                        existing.elementId, existing.depth
                    )
                    break
                }
            }
            
            if (!isSubsetOfExisting) {
                result.add(table)
            }
        }
        
        logger.debug(
            "Deduplication: {} tables -> {} unique tables",
            tables.size, result.size
        )
        
        return result
    }
}
