package io.deepsearch.domain.detection

import io.deepsearch.domain.services.GridCell
import io.deepsearch.domain.services.ITableGridDetectorService
import io.deepsearch.domain.services.TableDetectionBoundingBox
import io.deepsearch.domain.services.TableGridResult

/**
 * Robust table grid detection using edge clustering.
 * 
 * This service analyzes bounding boxes of elements to identify table structures.
 * It handles:
 * - Variable cell sizes (rows can have different heights)
 * - Merged cells (spanning multiple rows/columns)
 * - Sparse tables
 * - Uniform spacing (where gap-based analysis fails)
 * 
 * The algorithm uses edge clustering (not gap thresholding):
 * 1. Collect all edges (top/bottom for rows, left/right for columns)
 * 2. Cluster edges that are close together (aligned elements)
 * 3. Each cluster represents a boundary zone
 * 4. Detect table if EITHER axis shows structure (high recall)
 * 
 * Design principle: Prioritize recall over precision.
 * False positives are acceptable; missing tables is not.
 */
class TableGridDetectorService : ITableGridDetectorService {
    
    companion object {
        // Minimum clustering tolerance (pixels)
        private const val MIN_CLUSTER_TOLERANCE = 15.0
        
        // Maximum clustering tolerance (pixels) 
        private const val MAX_CLUSTER_TOLERANCE = 50.0
        
        // Tolerance as fraction of median element size
        private const val TOLERANCE_SIZE_FACTOR = 0.6
        
        // Minimum confidence to accept as table (lowered for high recall)
        private const val MIN_CONFIDENCE = 0.35
        
        // Minimum fill ratio (lowered for sparse tables)
        private const val MIN_FILL_RATIO = 0.15
    }
    
    /**
     * Detect if the given bounding boxes form a table grid.
     * 
     * @param boxes Map of element ID to bounding box
     * @return Detection result with grid structure if found
     */
    override fun detectTable(boxes: Map<String, TableDetectionBoundingBox>): TableGridResult {
        if (boxes.size < 4) {
            return TableGridResult.NO_TABLE.copy(reason = "Need at least 4 boxes")
        }

        val boxList = boxes.entries.toList()
        val boundingBoxes = boxList.map { it.value }
        
        // Compute element size statistics for adaptive tolerance
        val heights = boundingBoxes.map { it.bottom - it.top }.filter { it > 0 }
        val widths = boundingBoxes.map { it.right - it.left }.filter { it > 0 }
        val medianHeight = heights.sorted().getOrNull(heights.size / 2) ?: 20.0
        val medianWidth = widths.sorted().getOrNull(widths.size / 2) ?: 50.0
        
        // Step 1: Find row boundaries using edge clustering on horizontal edges
        val horizontalEdges = boundingBoxes.flatMap { listOf(it.top, it.bottom) }
        val rowTolerance = computeClusterTolerance(medianHeight)
        val rowBoundaries = findBoundariesByEdgeClustering(horizontalEdges, rowTolerance)
        
        // Step 2: Find column boundaries using edge clustering on vertical edges
        val verticalEdges = boundingBoxes.flatMap { listOf(it.left, it.right) }
        val colTolerance = computeClusterTolerance(medianWidth)
        val colBoundaries = findBoundariesByEdgeClustering(verticalEdges, colTolerance)
        
        val rowCount = maxOf(0, rowBoundaries.size - 1)
        val colCount = maxOf(0, colBoundaries.size - 1)
        
        // Either axis showing structure is enough (high recall)
        val hasRowStructure = rowCount >= 2
        val hasColStructure = colCount >= 2
        
        if (!hasRowStructure && !hasColStructure) {
            return TableGridResult.NO_TABLE.copy(
                reason = "No structure found: ${rowCount} rows, ${colCount} cols"
            )
        }
        
        // If only one axis has structure, ensure minimum grid size
        if (rowCount * colCount < 2) {
            return TableGridResult.NO_TABLE.copy(
                reason = "Grid too small: ${rowCount}×${colCount}"
            )
        }

        // Step 3: Assign each box to grid cells (handling merged cells)
        val cells = assignBoxesToGrid(boxList, rowBoundaries, colBoundaries)

        // Step 4: Validate the grid and calculate confidence (relaxed for high recall)
        val validation = validateGrid(
            cells = cells, 
            rowCount = rowCount, 
            colCount = colCount, 
            totalBoxes = boxes.size,
            hasRowStructure = hasRowStructure,
            hasColStructure = hasColStructure
        )

        return TableGridResult(
            isTable = validation.isValid,
            confidence = validation.confidence,
            rowCount = rowCount,
            colCount = colCount,
            rowBoundaries = rowBoundaries,
            colBoundaries = colBoundaries,
            cells = cells,
            reason = validation.reason
        )
    }
    
    /**
     * Compute adaptive clustering tolerance based on element size.
     * Larger elements get larger tolerance.
     */
    private fun computeClusterTolerance(medianSize: Double): Double {
        val computed = medianSize * TOLERANCE_SIZE_FACTOR
        return maxOf(MIN_CLUSTER_TOLERANCE, minOf(MAX_CLUSTER_TOLERANCE, computed))
    }

    /**
     * Find grid boundaries using edge clustering.
     * 
     * Key insight: Elements in the same row/column have edges that cluster together.
     * Instead of looking for "big gaps", we cluster aligned edges.
     * 
     * This works even for uniform spacing where all gaps are similar.
     */
    private fun findBoundariesByEdgeClustering(edges: List<Double>, tolerance: Double): List<Double> {
        if (edges.size < 2) return emptyList()

        // Sort all edges
        val sorted = edges.sorted()
        
        // Cluster edges that are within tolerance of each other
        val clusters = mutableListOf<MutableList<Double>>()
        var currentCluster = mutableListOf(sorted[0])
        
        for (i in 1 until sorted.size) {
            val edge = sorted[i]
            // Check if this edge is close to any edge in the current cluster
            // We use the cluster's min value as reference to ensure cluster cohesion
            val clusterMin = currentCluster.first()
            val clusterMax = currentCluster.last()
            
            // Edge belongs to current cluster if it's within tolerance of the cluster range
            if (edge - clusterMin <= tolerance * 2 && edge - clusterMax <= tolerance) {
                currentCluster.add(edge)
            } else {
                // Start new cluster
                clusters.add(currentCluster)
                currentCluster = mutableListOf(edge)
            }
        }
        // Don't forget last cluster
        clusters.add(currentCluster)
        
        // Each cluster represents a boundary zone
        // Return the center of each cluster as the boundary
        val boundaries = clusters.map { cluster ->
            cluster.average()
        }
        
        return boundaries
    }

    /**
     * Assign each box to grid cells, handling merged cells.
     * A box might span multiple rows and/or columns.
     */
    private fun assignBoxesToGrid(
        boxList: List<Map.Entry<String, TableDetectionBoundingBox>>,
        rowBoundaries: List<Double>,
        colBoundaries: List<Double>
    ): List<GridCell> {
        if (rowBoundaries.size < 2 || colBoundaries.size < 2) {
            return emptyList()
        }
        
        val cells = mutableListOf<GridCell>()
        // Use larger tolerance for assignment to handle edge cases
        val tolerance = 10.0

        for ((elementId, box) in boxList) {
            // Find which rows this box spans
            val rowStart = findBoundaryIndex(box.top, rowBoundaries, tolerance, BoundaryType.START)
            val rowEnd = findBoundaryIndex(box.bottom, rowBoundaries, tolerance, BoundaryType.END)

            // Find which columns this box spans
            val colStart = findBoundaryIndex(box.left, colBoundaries, tolerance, BoundaryType.START)
            val colEnd = findBoundaryIndex(box.right, colBoundaries, tolerance, BoundaryType.END)

            if (rowStart >= 0 && rowEnd >= rowStart && colStart >= 0 && colEnd >= colStart) {
                cells.add(GridCell(
                    elementId = elementId,
                    rowStart = rowStart,
                    rowEnd = minOf(rowEnd, rowBoundaries.size - 2),
                    colStart = colStart,
                    colEnd = minOf(colEnd, colBoundaries.size - 2)
                ))
            }
        }

        return cells
    }

    /**
     * Find which grid cell index a coordinate falls into.
     */
    private fun findBoundaryIndex(
        value: Double,
        boundaries: List<Double>,
        tolerance: Double,
        type: BoundaryType
    ): Int {
        if (boundaries.size < 2) return -1
        
        for (i in 0 until boundaries.size - 1) {
            val cellStart = boundaries[i]
            val cellEnd = boundaries[i + 1]

            when (type) {
                BoundaryType.START -> {
                    // For start position: value should be at or after cellStart
                    if (value >= cellStart - tolerance && value < cellEnd + tolerance) {
                        return i
                    }
                }
                BoundaryType.END -> {
                    // For end position: value should be at or before cellEnd
                    if (value > cellStart - tolerance && value <= cellEnd + tolerance) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    /**
     * Validate that the detected grid looks like a real table.
     * Relaxed thresholds for high recall - prefer false positives over missed tables.
     */
    private fun validateGrid(
        cells: List<GridCell>,
        rowCount: Int,
        colCount: Int,
        totalBoxes: Int,
        hasRowStructure: Boolean,
        hasColStructure: Boolean
    ): ValidationResult {
        // Very relaxed minimum cells check
        if (cells.size < 2) {
            return ValidationResult(
                isValid = false,
                confidence = 0.0,
                reason = "Too few cells assigned to grid (${cells.size})"
            )
        }

        // Create occupancy grid
        val occupied = Array(rowCount) { BooleanArray(colCount) }

        for (cell in cells) {
            for (r in cell.rowStart..cell.rowEnd) {
                for (c in cell.colStart..cell.colEnd) {
                    if (r < rowCount && c < colCount) {
                        occupied[r][c] = true
                    }
                }
            }
        }

        // Count filled cells
        var filledCount = 0
        for (r in 0 until rowCount) {
            for (c in 0 until colCount) {
                if (occupied[r][c]) filledCount++
            }
        }

        val totalCells = rowCount * colCount
        val fillRatio = if (totalCells > 0) filledCount.toDouble() / totalCells else 0.0

        // Check row consistency
        val rowFills = occupied.map { row -> row.count { it } }
        val avgRowFill = if (rowFills.isNotEmpty()) rowFills.average() else 0.0
        val rowVariance = if (rowFills.isNotEmpty() && avgRowFill > 0) {
            rowFills.map { (it - avgRowFill).let { diff -> diff * diff } }.average()
        } else 0.0
        val rowConsistency = 1.0 / (1.0 + rowVariance / maxOf(1.0, avgRowFill * avgRowFill))

        // Calculate confidence (relaxed scoring for high recall)
        var confidence = 0.0

        // Base confidence from having structure on either axis
        if (hasRowStructure && hasColStructure) {
            confidence += 0.3  // Both axes have structure - strong signal
        } else if (hasRowStructure || hasColStructure) {
            confidence += 0.2  // One axis has structure - still valid
        }

        // Confidence from fill ratio (relaxed thresholds)
        confidence += when {
            fillRatio >= 0.5 -> 0.25
            fillRatio >= 0.3 -> 0.2
            fillRatio >= 0.15 -> 0.1
            else -> 0.0
        }

        // Confidence from row consistency
        confidence += rowConsistency * 0.25

        // Bonus for reasonable table size
        if (rowCount >= 3) confidence += 0.1
        if (colCount >= 2) confidence += 0.05
        if (rowCount >= 5) confidence += 0.05

        // Small penalty for very sparse tables, but don't reject them
        if (fillRatio < MIN_FILL_RATIO) {
            confidence *= 0.7
        }

        confidence = minOf(1.0, maxOf(0.0, confidence))

        // Relaxed validation - accept if confidence meets threshold OR if we have good structure
        val hasMinimumGrid = (rowCount >= 2 && colCount >= 1) || (rowCount >= 1 && colCount >= 2)
        val isValid = hasMinimumGrid && (confidence >= MIN_CONFIDENCE || fillRatio >= 0.4)

        return ValidationResult(
            isValid = isValid,
            confidence = confidence,
            reason = if (isValid) {
                "Grid ${rowCount}×${colCount}, ${(fillRatio * 100).toInt()}% filled, conf=${String.format("%.2f", confidence)}"
            } else {
                "Low confidence: fill=${(fillRatio * 100).toInt()}%, conf=${String.format("%.2f", confidence)}, rows=$rowCount, cols=$colCount"
            }
        )
    }

    private enum class BoundaryType {
        START, END
    }

    private data class ValidationResult(
        val isValid: Boolean,
        val confidence: Double,
        val reason: String
    )
}
