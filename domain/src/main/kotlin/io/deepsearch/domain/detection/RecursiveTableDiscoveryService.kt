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
import kotlin.math.abs

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
    val maxDepth: Int = 15,
    /** Tolerance for column alignment (pixels) */
    val columnAlignmentTolerance: Double = 10.0,
    /** Maximum vertical gap between adjacent tables to consider them related (pixels) */
    val maxVerticalGap: Double = 50.0,
    /** Tolerance for bounding box deduplication (pixels) - tables with bounds within this tolerance are considered duplicates */
    val boundingBoxDeduplicationTolerance: Double = 20.0
)

/**
 * Internal representation of a table candidate during the collection phase.
 */
private data class TableCandidate(
    val localElementId: String,
    val containerDataId: String,
    val containerHtml: String,
    val depth: Int,
    val leafElementIds: Set<String>,
    val leafBoundingBoxes: Map<String, TableDetectionBoundingBox>,
    val gridResult: TableGridResult,
    val boundingBox: TableDetectionBoundingBox?
)

/**
 * Represents a group of spatially related table candidates.
 */
private data class SpatialGroup(
    val candidates: List<TableCandidate>,
    val combinedLeafIds: Set<String>,
    val expectedRowCount: Int,
    val columnCount: Int
)

/**
 * Implementation of recursive table discovery using DOM traversal and spatial analysis.
 * 
 * Uses a multi-phase algorithm:
 * 
 * Phase 1: Exhaustive Candidate Collection
 *   - Traverse entire DOM tree
 *   - Detect grid patterns at EVERY level (no early termination)
 *   - Record which leaf elements each candidate uses
 * 
 * Phase 2: Spatial Grouping
 *   - Group candidates by column alignment (same column structure)
 *   - Within groups, verify vertical adjacency (stacked tables)
 * 
 * Phase 3: LCA Selection
 *   - For each spatial group, find the Lowest Common Ancestor (LCA)
 *   - Validate that LCA forms a coherent merged table
 *   - If valid, select LCA; if not, keep candidates separate
 * 
 * Phase 4: Leaf Claiming
 *   - Process remaining ungrouped candidates
 *   - Use greedy leaf claiming to avoid duplicates
 */
class RecursiveTableDiscoveryService(
    private val tableGridDetectorService: ITableGridDetectorService,
) : IRecursiveTableDiscoveryService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val config: TableDiscoveryConfig = TableDiscoveryConfig()

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
        
        // Find the root container element (first element with data-ds-local)
        val rootElement = body.selectFirst("[data-ds-local]") ?: return emptyList()
        
        // Compute leaf boxes once (boxes that don't contain other boxes)
        val allLeafBoxes = filterToLeafBoxes(detectionBoxes)
        
        // Run the multi-phase algorithm
        // Note: For direct discoverTables call, we use the root element's data-ds-local as a fallback ID
        val rootLocalId = rootElement.attr("data-ds-local")
        return runMultiPhaseDiscovery(rootElement, detectionBoxes, allLeafBoxes, rootLocalId, containerHtml)
    }

    override fun discoverTablesFromHiddenContainers(
        hiddenContainerData: IBrowserPage.HiddenContainerBoundingBoxes
    ): List<DiscoveredTable> {
        val allSelected = mutableListOf<DiscoveredTable>()
        
        logger.info("=" .repeat(80))
        logger.info("STARTING TABLE DISCOVERY")
        logger.info("Processing {} hidden containers", hiddenContainerData.hiddenContainers.size)
        logger.info("=" .repeat(80))
        
        for ((containerIndex, container) in hiddenContainerData.hiddenContainers.withIndex()) {
            logger.debug("Processing container {}/{}: [{}] with {} elements", 
                containerIndex + 1, hiddenContainerData.hiddenContainers.size,
                container.containerDataId, container.elements.size)
            
            // Parse the container HTML directly
            val doc = Jsoup.parse(container.containerHtml)
            val body = doc.body()
            
            // Find the root container element (first element with data-ds-local)
            val containerElement = body.selectFirst("[data-ds-local]")
            if (containerElement == null) {
                logger.debug("No data-ds-local element found in container HTML")
                continue
            }
            
            // Convert bounding boxes to detection format
            val detectionBoxes = container.elements.mapValues { (_, box) ->
                TableDetectionBoundingBox(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom
                )
            }
            
            // Compute leaf boxes once for this container
            val allLeafBoxes = filterToLeafBoxes(detectionBoxes)
            
            // Debug: log leaf boxes count and non-zero boxes
            val nonZeroLeafBoxes = allLeafBoxes.filterValues { it.width > 0 && it.height > 0 }
            logger.debug("[Container {}] Total boxes: {}, Leaf boxes: {}, Non-zero leaf boxes: {}", 
                containerIndex + 1, detectionBoxes.size, allLeafBoxes.size, nonZeroLeafBoxes.size)
            
            if (nonZeroLeafBoxes.size < 6) {
                logger.warn("[Container {}] INSUFFICIENT non-zero leaf boxes ({}) - likely bounding box capture issue", 
                    containerIndex + 1, nonZeroLeafBoxes.size)
            }
            
            // Run the multi-phase algorithm
            val selected = runMultiPhaseDiscovery(
                containerElement, 
                detectionBoxes, 
                allLeafBoxes,
                container.containerDataId,
                container.containerHtml
            )
            
            allSelected.addAll(selected)
        }
        
        logger.info("-".repeat(60))
        logger.info("PRE-DEDUP: {} tables from all containers", allSelected.size)
        logger.info("-".repeat(60))
        
        // ==================== CROSS-CONTAINER DEDUPLICATION ====================
        // Same physical table may be captured in multiple overlapping hidden containers.
        // Deduplicate by bounding box - tables covering the same screen region are duplicates.
        val deduplicated = deduplicateByBoundingBox(allSelected)
        
        logger.info("=" .repeat(80))
        logger.info("DISCOVERY COMPLETE: {} tables after deduplication (removed {} duplicates)", 
            deduplicated.size, allSelected.size - deduplicated.size)
        logger.info("=" .repeat(80))
        
        return deduplicated
    }
    
    /**
     * Deduplicate tables across containers by content.
     * 
     * Tables that have the same actual content (extracted HTML) are considered duplicates.
     * This is more robust than bounding box comparison because different containers
     * can occupy similar screen positions at different times.
     * 
     * When duplicates are found, keep the one with highest confidence.
     */
    private fun deduplicateByBoundingBox(tables: List<DiscoveredTable>): List<DiscoveredTable> {
        if (tables.size <= 1) return tables
        
        // Extract content signature for each table
        data class TableWithSignature(
            val table: DiscoveredTable,
            val contentSignature: String,
            val bounds: TableDetectionBoundingBox?
        )
        
        val tablesWithSignatures = tables.map { table ->
            val contentSignature = extractContentSignature(table)
            val bounds = computeTableBounds(table.elementBoundingBoxes)
            TableWithSignature(table, contentSignature, bounds)
        }
        
        // Group tables by content signature
        val groups = mutableListOf<MutableList<TableWithSignature>>()
        
        for (tws in tablesWithSignatures) {
            // Find an existing group with the same content signature
            // NOTE: We ONLY use content signature for cross-container deduplication.
            // Bounding box overlap is NOT reliable because hidden containers (like accordions)
            // all expand to the same screen position, causing false-positive deduplication.
            // Different accordion sections would have overlapping bounds but different content.
            val matchingGroup = groups.find { group ->
                group.any { existing -> 
                    // Content signature match = definitely duplicate
                    existing.contentSignature == tws.contentSignature
                }
            }
            
            if (matchingGroup != null) {
                matchingGroup.add(tws)
            } else {
                // Start a new group
                groups.add(mutableListOf(tws))
            }
        }
        
        // From each group, keep the best table (highest confidence, then most leaves)
        val deduplicated = mutableListOf<DiscoveredTable>()
        
        for ((groupIndex, group) in groups.withIndex()) {
            if (group.size == 1) {
                deduplicated.add(group.first().table)
            } else {
                // Sort by confidence desc, then by leaf count desc
                val sorted = group.sortedWith(
                    compareByDescending<TableWithSignature> { it.table.gridResult.confidence }
                        .thenByDescending { it.table.elementBoundingBoxes.size }
                )
                
                val best = sorted.first().table
                deduplicated.add(best)
                
                logger.info("[Dedup] Group {}: {} duplicates merged -> keeping {} (conf={:.2f}, {} leaves)",
                    groupIndex + 1, group.size, best.localElementId,
                    best.gridResult.confidence, best.elementBoundingBoxes.size)
                
                // Log what was removed
                for (duplicate in sorted.drop(1)) {
                    logger.debug("[Dedup]   - Removed {} (conf={:.2f}, {} leaves, sig={}...)",
                        duplicate.table.localElementId, duplicate.table.gridResult.confidence,
                        duplicate.table.elementBoundingBoxes.size,
                        duplicate.contentSignature.take(50))
                }
            }
        }
        
        return deduplicated
    }
    
    /**
     * Extract a content signature from the table's HTML element.
     * 
     * This extracts the actual HTML snippet for the table's localElementId from containerHtml,
     * then creates a normalized text signature that can be compared across containers.
     */
    private fun extractContentSignature(table: DiscoveredTable): String {
        val doc = Jsoup.parse(table.containerHtml)
        val element = doc.selectFirst("[data-ds-local='${table.localElementId}']")
        
        if (element == null) {
            // Fallback: use bounding box coordinates as signature
            val bounds = computeTableBounds(table.elementBoundingBoxes)
            return if (bounds != null) {
                "bounds:${bounds.left.toInt()},${bounds.top.toInt()},${bounds.right.toInt()},${bounds.bottom.toInt()}"
            } else {
                "unknown:${table.localElementId}"
            }
        }
        
        // Extract and normalize text content (remove whitespace, lowercase)
        val textContent = element.text()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        
        // Create a signature from first N characters of content + element structure hint
        val structureHint = "${element.tagName()}:${element.childrenSize()}"
        val contentSample = textContent.take(200)
        
        return "$structureHint|$contentSample"
    }
    
    /**
     * Check if two sets of leaf bounding boxes significantly overlap.
     * 
     * This uses Intersection over Union (IoU) of the combined bounds.
     * If IoU > threshold, the tables likely represent the same physical region.
     */
    private fun leafBoxesOverlap(
        boxes1: Map<String, TableDetectionBoundingBox>,
        boxes2: Map<String, TableDetectionBoundingBox>
    ): Boolean {
        val bounds1 = computeTableBounds(boxes1) ?: return false
        val bounds2 = computeTableBounds(boxes2) ?: return false
        
        // Calculate intersection
        val intersectLeft = maxOf(bounds1.left, bounds2.left)
        val intersectTop = maxOf(bounds1.top, bounds2.top)
        val intersectRight = minOf(bounds1.right, bounds2.right)
        val intersectBottom = minOf(bounds1.bottom, bounds2.bottom)
        
        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return false // No intersection
        }
        
        val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val area1 = bounds1.width * bounds1.height
        val area2 = bounds2.width * bounds2.height
        val unionArea = area1 + area2 - intersectionArea
        
        val iou = if (unionArea > 0) intersectionArea / unionArea else 0.0
        
        // High IoU threshold - tables must significantly overlap to be considered duplicates
        return iou > 0.85
    }
    
    /**
     * Compute the combined bounding box for a table's leaf elements.
     */
    private fun computeTableBounds(elementBoxes: Map<String, TableDetectionBoundingBox>): TableDetectionBoundingBox? {
        if (elementBoxes.isEmpty()) return null
        
        var minLeft = Double.MAX_VALUE
        var minTop = Double.MAX_VALUE
        var maxRight = Double.MIN_VALUE
        var maxBottom = Double.MIN_VALUE
        
        for ((_, box) in elementBoxes) {
            if (box.left < minLeft) minLeft = box.left
            if (box.top < minTop) minTop = box.top
            if (box.right > maxRight) maxRight = box.right
            if (box.bottom > maxBottom) maxBottom = box.bottom
        }
        
        return TableDetectionBoundingBox(
            left = minLeft,
            top = minTop,
            right = maxRight,
            bottom = maxBottom
        )
    }
    
    /**
     * Check if two bounding boxes are similar (within tolerance).
     */
    private fun boundsAreSimilar(a: TableDetectionBoundingBox, b: TableDetectionBoundingBox): Boolean {
        val tolerance = config.boundingBoxDeduplicationTolerance
        
        return abs(a.left - b.left) <= tolerance &&
               abs(a.top - b.top) <= tolerance &&
               abs(a.right - b.right) <= tolerance &&
               abs(a.bottom - b.bottom) <= tolerance
    }

    /**
     * Run the multi-phase discovery algorithm.
     */
    private fun runMultiPhaseDiscovery(
        rootElement: Element,
        allBoundingBoxes: Map<String, TableDetectionBoundingBox>,
        allLeafBoxes: Map<String, TableDetectionBoundingBox>,
        containerDataId: String,
        containerHtml: String
    ): List<DiscoveredTable> {
        
        // ==================== PHASE 1: Collect all candidates ====================
        logger.debug("-".repeat(60))
        logger.debug("PHASE 1: Collecting all candidates")
        logger.debug("-".repeat(60))
        
        val candidates = collectAllCandidates(
            rootElement, 
            allBoundingBoxes, 
            allLeafBoxes,
            0, 
            containerDataId, 
            containerHtml
        )
        
        logger.info("[Phase1] Collected {} candidates total", candidates.size)
        
        if (candidates.isEmpty()) {
            return emptyList()
        }
        
        // Log candidate summary
        val depthGroups = candidates.groupBy { it.depth }
        for ((depth, group) in depthGroups.entries.sortedBy { it.key }) {
            logger.debug("[Phase1] Depth {}: {} candidates", depth, group.size)
        }
        
        // ==================== PHASE 2: Spatial Grouping ====================
        logger.debug("-".repeat(60))
        logger.debug("PHASE 2: Spatial Grouping")
        logger.debug("-".repeat(60))
        
        val spatialGroups = groupByColumnAlignment(candidates)
        
        logger.info("[Phase2] Found {} spatial groups from {} candidates", 
            spatialGroups.size, candidates.size)
        
        for ((groupIndex, group) in spatialGroups.withIndex()) {
            logger.debug("[Phase2] Group {}: {} candidates, {} cols, expected {} rows, leaves={}",
                groupIndex + 1, group.candidates.size, group.columnCount, 
                group.expectedRowCount, group.combinedLeafIds.size)
            for (c in group.candidates) {
                logger.debug("[Phase2]   - {} (depth={}, {}x{}, {} leaves)",
                    c.localElementId, c.depth, c.gridResult.rowCount, c.gridResult.colCount,
                    c.leafElementIds.size)
            }
        }
        
        // ==================== PHASE 3: LCA Selection ====================
        logger.debug("-".repeat(60))
        logger.debug("PHASE 3: LCA Selection")
        logger.debug("-".repeat(60))
        
        val selected = mutableListOf<DiscoveredTable>()
        val processedCandidateIds = mutableSetOf<String>()
        val claimedLeafIds = mutableSetOf<String>()
        
        // Build a map of localId -> candidate for quick lookup
        val candidateById = candidates.associateBy { it.localElementId }
        
        for ((groupIndex, group) in spatialGroups.withIndex()) {
            if (group.candidates.size <= 1) {
                // Single candidate, no LCA needed - will be handled in Phase 4
                logger.debug("[Phase3] Group {} has single candidate, deferring to Phase 4", groupIndex + 1)
                continue
            }
            
            // Find LCA for this group
            val lcaCandidate = findLCACandidate(group, candidateById)
            
            if (lcaCandidate != null) {
                // LCA was already detected as a valid table in Phase 1
                // Trust the grid detector - if it passed detection, the spatial structure is coherent
                val lcaRowCount = lcaCandidate.gridResult.rowCount
                val lcaColCount = lcaCandidate.gridResult.colCount
                
                logger.debug("[Phase3] Group {} LCA candidate: {} ({}x{}, conf={:.2f}, {} leaves)",
                    groupIndex + 1, lcaCandidate.localElementId, 
                    lcaRowCount, lcaColCount, lcaCandidate.gridResult.confidence,
                    lcaCandidate.leafElementIds.size)
                
                // Trust grid detection - if LCA is a complete table, prefer it over fragments
                // No row count heuristics - the grid detector already validated the structure
                if (isCompleteTable(lcaCandidate.gridResult)) {
                    // LCA is valid - select it instead of children
                    logger.info("[Phase3] Group {} MERGED: {} subsumes {} children ({}x{} table, {} leaves)",
                        groupIndex + 1, lcaCandidate.localElementId, 
                        group.candidates.size, lcaRowCount, lcaColCount,
                        lcaCandidate.leafElementIds.size)
                    
                    selected.add(toDiscoveredTable(lcaCandidate))
                    claimedLeafIds.addAll(lcaCandidate.leafElementIds)
                    
                    // Mark all children as processed
                    for (child in group.candidates) {
                        processedCandidateIds.add(child.localElementId)
                    }
                    processedCandidateIds.add(lcaCandidate.localElementId)
                } else {
                    logger.debug("[Phase3] Group {} LCA incomplete ({}x{}, conf={:.2f})",
                        groupIndex + 1, lcaRowCount, lcaColCount, lcaCandidate.gridResult.confidence)
                    // LCA incomplete - children will be handled in Phase 4
                }
            } else {
                logger.debug("[Phase3] Group {} has no LCA candidate containing all {} leaves",
                    groupIndex + 1, group.combinedLeafIds.size)
            }
        }
        
        logger.info("[Phase3] Selected {} merged tables, {} candidates processed",
            selected.size, processedCandidateIds.size)
        
        // ==================== PHASE 4: Leaf Claiming for Remaining ====================
        logger.debug("-".repeat(60))
        logger.debug("PHASE 4: Leaf Claiming for Remaining Candidates")
        logger.debug("-".repeat(60))
        
        // Get unprocessed candidates, sorted by depth (deepest first)
        val remainingCandidates = candidates
            .filter { it.localElementId !in processedCandidateIds }
            .sortedByDescending { it.depth }
        
        logger.debug("[Phase4] Processing {} remaining candidates", remainingCandidates.size)
        
        for (candidate in remainingCandidates) {
            // Which of this candidate's leaves are still unclaimed?
            val availableLeaves = candidate.leafElementIds - claimedLeafIds
            
            if (availableLeaves.isEmpty()) {
                logger.debug("[Phase4] {} SKIP: all {} leaves claimed",
                    candidate.localElementId, candidate.leafElementIds.size)
                continue
            }
            
            if (availableLeaves == candidate.leafElementIds) {
                // All leaves available
                if (isCompleteTable(candidate.gridResult)) {
                    selected.add(toDiscoveredTable(candidate))
                    claimedLeafIds.addAll(candidate.leafElementIds)
                    
                    logger.info("[Phase4] {} SELECTED: {}x{} table, {} leaves",
                        candidate.localElementId,
                        candidate.gridResult.rowCount, candidate.gridResult.colCount,
                        candidate.leafElementIds.size)
                } else {
                    logger.debug("[Phase4] {} SKIP: incomplete ({}x{})",
                        candidate.localElementId,
                        candidate.gridResult.rowCount, candidate.gridResult.colCount)
                }
            } else {
                // Partial leaves available - re-detect
                val availableBoxes = availableLeaves.associateWith { allLeafBoxes[it]!! }
                
                if (availableBoxes.size >= config.minLeafElements) {
                    val newGridResult = tableGridDetectorService.detectTable(availableBoxes)
                    
                    if (newGridResult.isTable && isCompleteTable(newGridResult)) {
                        selected.add(DiscoveredTable(
                            localElementId = candidate.localElementId,
                            containerDataId = candidate.containerDataId,
                            containerHtml = candidate.containerHtml,
                            gridResult = newGridResult,
                            depth = candidate.depth,
                            elementBoundingBoxes = availableBoxes
                        ))
                        claimedLeafIds.addAll(availableLeaves)
                        
                        logger.info("[Phase4] {} SELECTED (partial): {}x{} from {} leaves",
                            candidate.localElementId,
                            newGridResult.rowCount, newGridResult.colCount,
                            availableLeaves.size)
                    } else {
                        logger.debug("[Phase4] {} SKIP: partial leaves don't form table",
                            candidate.localElementId)
                    }
                } else {
                    logger.debug("[Phase4] {} SKIP: only {} leaves available",
                        candidate.localElementId, availableLeaves.size)
                }
            }
        }
        
        logger.info("[Phase4] Final selection: {} tables, {} leaves claimed",
            selected.size, claimedLeafIds.size)
        
        return selected
    }

    // ==================== PHASE 1: Exhaustive Candidate Collection ====================

    /**
     * Collect ALL table candidates at ALL depths without early termination.
     */
    private fun collectAllCandidates(
        element: Element,
        allBoundingBoxes: Map<String, TableDetectionBoundingBox>,
        allLeafBoxes: Map<String, TableDetectionBoundingBox>,
        depth: Int,
        containerDataId: String,
        containerHtml: String
    ): List<TableCandidate> {
        if (depth > config.maxDepth) {
            return emptyList()
        }
        
        val candidates = mutableListOf<TableCandidate>()
        val localId = element.attr("data-ds-local")
        
        if (localId.isNotBlank()) {
            val containerBox = allBoundingBoxes[localId]
            
            if (containerBox != null && 
                containerBox.height >= config.minHeight && 
                containerBox.width >= config.minWidth) {
                
                // Find leaf boxes contained within this element
                val containedLeafIds = allLeafBoxes.filterKeys { leafId ->
                    leafId != localId && isContainedIn(allLeafBoxes[leafId]!!, containerBox)
                }.keys
                
                if (containedLeafIds.size >= config.minLeafElements) {
                    val leafBoxesForDetection = containedLeafIds.associateWith { allLeafBoxes[it]!! }
                    val gridResult = tableGridDetectorService.detectTable(leafBoxesForDetection)
                    
                    if (gridResult.isTable && gridResult.confidence >= config.minConfidence) {
                        candidates.add(TableCandidate(
                            localElementId = localId,
                            containerDataId = containerDataId,
                            containerHtml = containerHtml,
                            depth = depth,
                            leafElementIds = containedLeafIds,
                            leafBoundingBoxes = leafBoxesForDetection,
                            gridResult = gridResult,
                            boundingBox = containerBox
                        ))
                        
                        logger.debug("[Phase1] depth={} {} candidate: {}x{}, {} leaves, conf={:.2f}",
                            depth, localId, gridResult.rowCount, gridResult.colCount,
                            containedLeafIds.size, gridResult.confidence)
                    } else if (depth == 0 && containedLeafIds.size >= 20) {
                        // Debug: log root-level elements with many leaves that failed grid detection
                        logger.debug("[Phase1] depth={} {} FAILED grid detection: {} leaves, isTable={}, conf={:.2f}, reason={}",
                            depth, localId, containedLeafIds.size, gridResult.isTable, gridResult.confidence, gridResult.reason)
                    }
                } else if (depth == 0 && localId == "ds-local-0") {
                    // Debug: log root element leaf count
                    logger.debug("[Phase1] depth=0 {} has only {} contained leaf boxes (need {})",
                        localId, containedLeafIds.size, config.minLeafElements)
                }
            } else if (depth == 0 && localId == "ds-local-0") {
                // Debug: log root element size issues
                val boxInfo = containerBox?.let { "size=${it.width}x${it.height}" } ?: "null"
                logger.debug("[Phase1] depth=0 {} container box issue: {} (need {}x{})",
                    localId, boxInfo, config.minWidth, config.minHeight)
            }
        }
        
        // Always recurse into children
        for (child in element.children()) {
            candidates.addAll(collectAllCandidates(
                child, allBoundingBoxes, allLeafBoxes,
                depth + 1, containerDataId, containerHtml
            ))
        }
        
        return candidates
    }

    // ==================== PHASE 2: Spatial Grouping ====================

    /**
     * Group candidates by column alignment and vertical adjacency.
     * 
     * Candidates are considered related if:
     * 1. They have the same column count
     * 2. Their column boundaries align within tolerance
     * 3. They are vertically adjacent (stacked)
     */
    private fun groupByColumnAlignment(candidates: List<TableCandidate>): List<SpatialGroup> {
        if (candidates.isEmpty()) return emptyList()
        
        // Group by column count first
        val byColumnCount = candidates.groupBy { it.gridResult.colCount }
        
        val spatialGroups = mutableListOf<SpatialGroup>()
        
        for ((colCount, colCandidates) in byColumnCount) {
            if (colCandidates.size <= 1) {
                // Single candidate with this column count - make it its own group
                val c = colCandidates.first()
                spatialGroups.add(SpatialGroup(
                    candidates = listOf(c),
                    combinedLeafIds = c.leafElementIds,
                    expectedRowCount = c.gridResult.rowCount,
                    columnCount = colCount
                ))
                continue
            }
            
            // Multiple candidates with same column count
            // Group by vertical adjacency
            val adjacencyGroups = groupByVerticalAdjacency(colCandidates)
            
            for (adjGroup in adjacencyGroups) {
                val combinedLeaves = adjGroup.flatMap { it.leafElementIds }.toSet()
                val totalRows = adjGroup.sumOf { it.gridResult.rowCount }
                
                spatialGroups.add(SpatialGroup(
                    candidates = adjGroup,
                    combinedLeafIds = combinedLeaves,
                    expectedRowCount = totalRows,
                    columnCount = colCount
                ))
            }
        }
        
        return spatialGroups
    }

    /**
     * Group candidates by vertical adjacency.
     * Candidates that are vertically stacked (small gap between them) are grouped together.
     */
    private fun groupByVerticalAdjacency(candidates: List<TableCandidate>): List<List<TableCandidate>> {
        if (candidates.size <= 1) return listOf(candidates)
        
        // Sort by top position
        val sorted = candidates.filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.top }
        
        if (sorted.isEmpty()) return listOf(candidates)
        
        val groups = mutableListOf<MutableList<TableCandidate>>()
        var currentGroup = mutableListOf(sorted.first())
        
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            
            val prevBottom = prev.boundingBox!!.bottom
            val currTop = curr.boundingBox!!.top
            val gap = currTop - prevBottom
            
            // Check if columns align
            val columnsAlign = checkColumnAlignment(prev, curr)
            
            if (gap <= config.maxVerticalGap && columnsAlign) {
                // Adjacent and aligned - add to current group
                currentGroup.add(curr)
                logger.debug("[Phase2] {} and {} are adjacent (gap={}px, columns align)",
                    prev.localElementId, curr.localElementId, gap.toInt())
            } else {
                // Not adjacent or columns don't align - start new group
                groups.add(currentGroup)
                currentGroup = mutableListOf(curr)
                
                if (gap > config.maxVerticalGap) {
                    logger.debug("[Phase2] {} and {} NOT adjacent (gap={}px > {}px)",
                        prev.localElementId, curr.localElementId, gap.toInt(), config.maxVerticalGap.toInt())
                } else {
                    logger.debug("[Phase2] {} and {} columns don't align",
                        prev.localElementId, curr.localElementId)
                }
            }
        }
        
        groups.add(currentGroup)
        
        // Add back any candidates without bounding boxes as single-element groups
        val withoutBox = candidates.filter { it.boundingBox == null }
        for (c in withoutBox) {
            groups.add(mutableListOf(c))
        }
        
        return groups
    }

    /**
     * Check if two candidates have aligned column boundaries.
     */
    private fun checkColumnAlignment(a: TableCandidate, b: TableCandidate): Boolean {
        val boxA = a.boundingBox ?: return false
        val boxB = b.boundingBox ?: return false
        
        // Check if left and right edges align
        val leftAligns = abs(boxA.left - boxB.left) <= config.columnAlignmentTolerance
        val rightAligns = abs(boxA.right - boxB.right) <= config.columnAlignmentTolerance
        
        return leftAligns && rightAligns
    }

    // ==================== PHASE 3: LCA Selection ====================

    /**
     * Find the LCA (Lowest Common Ancestor) candidate for a spatial group.
     * 
     * The LCA is the shallowest candidate whose leaves are a superset of all
     * the group's combined leaves.
     */
    private fun findLCACandidate(
        group: SpatialGroup,
        candidateById: Map<String, TableCandidate>
    ): TableCandidate? {
        // The LCA should contain all leaves from all children
        val targetLeaves = group.combinedLeafIds
        
        // Find candidates that contain all target leaves
        val containingCandidates = candidateById.values.filter { candidate ->
            candidate.leafElementIds.containsAll(targetLeaves)
        }
        
        if (containingCandidates.isEmpty()) {
            logger.debug("[Phase3] No candidate contains all {} leaves from group", targetLeaves.size)
            return null
        }
        
        // The LCA is the shallowest (lowest depth number) candidate
        // that contains all leaves but is not one of the group members
        val groupIds = group.candidates.map { it.localElementId }.toSet()
        
        val lcaCandidates = containingCandidates
            .filter { it.localElementId !in groupIds }
            .sortedBy { it.depth }
        
        if (lcaCandidates.isEmpty()) {
            logger.debug("[Phase3] No LCA found outside group members")
            return null
        }
        
        return lcaCandidates.first()
    }

    // ==================== Helper Methods ====================

    /**
     * Check if a grid result represents a "complete" table.
     */
    private fun isCompleteTable(gridResult: TableGridResult): Boolean {
        return gridResult.rowCount >= config.minRows &&
               gridResult.colCount >= config.minCols &&
               gridResult.confidence >= config.minConfidence
    }

    /**
     * Convert a TableCandidate to a DiscoveredTable.
     */
    private fun toDiscoveredTable(candidate: TableCandidate): DiscoveredTable {
        return DiscoveredTable(
            localElementId = candidate.localElementId,
            containerDataId = candidate.containerDataId,
            containerHtml = candidate.containerHtml,
            gridResult = candidate.gridResult,
            depth = candidate.depth,
            elementBoundingBoxes = candidate.leafBoundingBoxes
        )
    }

    /**
     * Filter to only leaf boxes (boxes that don't contain other boxes).
     */
    private fun filterToLeafBoxes(boxes: Map<String, TableDetectionBoundingBox>): Map<String, TableDetectionBoundingBox> {
        return boxes.filter { (id, box) ->
            val containsOther = boxes.any { (otherId, otherBox) ->
                otherId != id && isContainedIn(otherBox, box)
            }
            !containsOther
        }
    }

    /**
     * Check if inner box is contained within outer box.
     */
    private fun isContainedIn(inner: TableDetectionBoundingBox, outer: TableDetectionBoundingBox): Boolean {
        val tolerance = 5.0
        return inner.left >= outer.left - tolerance &&
               inner.top >= outer.top - tolerance &&
               inner.right <= outer.right + tolerance &&
               inner.bottom <= outer.bottom + tolerance &&
               (inner.width < outer.width * 0.95 || inner.height < outer.height * 0.95)
    }
}
