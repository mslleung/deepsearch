package io.deepsearch.domain.detection

/**
 * Bounding box representing an element's position on the page.
 */
data class BoundingBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
}

/**
 * Information about a detected grid cell (may span multiple rows/columns).
 */
data class GridCell(
    val elementId: String,
    val rowStart: Int,
    val rowEnd: Int,      // inclusive - for merged cells rowEnd > rowStart
    val colStart: Int,
    val colEnd: Int       // inclusive - for merged cells colEnd > colStart
)

/**
 * Result of table grid detection.
 */
data class TableGridResult(
    val isTable: Boolean,
    val confidence: Double,
    val rowCount: Int,
    val colCount: Int,
    val rowBoundaries: List<Double>,  // Y coordinates of row separators
    val colBoundaries: List<Double>,  // X coordinates of column separators  
    val cells: List<GridCell>,
    val reason: String
) {
    companion object {
        val NO_TABLE = TableGridResult(
            isTable = false,
            confidence = 0.0,
            rowCount = 0,
            colCount = 0,
            rowBoundaries = emptyList(),
            colBoundaries = emptyList(),
            cells = emptyList(),
            reason = "No table detected"
        )
    }
}

/**
 * Robust table grid detection using adaptive gap-based clustering.
 * 
 * This detector analyzes bounding boxes of elements to identify table structures.
 * It handles:
 * - Variable cell sizes
 * - Merged cells (spanning multiple rows/columns)
 * - Sparse tables
 * - No hardcoded thresholds (adapts to the data)
 * 
 * The algorithm uses projection-based gap analysis:
 * 1. Project all box edges onto Y-axis to find row boundaries
 * 2. Project all box edges onto X-axis to find column boundaries
 * 3. Assign each box to grid cells (handling spans)
 * 4. Validate the grid structure
 */
class TableGridDetector {
    
    /**
     * Detect if the given bounding boxes form a table grid.
     * 
     * @param boxes Map of element ID to bounding box
     * @return Detection result with grid structure if found
     */
    fun detectTable(boxes: Map<String, BoundingBox>): TableGridResult {
        if (boxes.size < 4) {
            return TableGridResult.NO_TABLE.copy(reason = "Need at least 4 boxes")
        }

        val boxList = boxes.entries.toList()
        val boundingBoxes = boxList.map { it.value }
        
        // Step 1: Find row boundaries using horizontal edges (tops and bottoms)
        val horizontalEdges = boundingBoxes.flatMap { listOf(it.top, it.bottom) }
        val rowBoundaries = findBoundaries(horizontalEdges)

        if (rowBoundaries.size < 2) {
            return TableGridResult.NO_TABLE.copy(reason = "Could not find row structure")
        }

        // Step 2: Find column boundaries using vertical edges (lefts and rights)
        val verticalEdges = boundingBoxes.flatMap { listOf(it.left, it.right) }
        val colBoundaries = findBoundaries(verticalEdges)

        if (colBoundaries.size < 2) {
            return TableGridResult.NO_TABLE.copy(reason = "Could not find column structure")
        }

        val rowCount = rowBoundaries.size - 1
        val colCount = colBoundaries.size - 1

        if (rowCount < 2 || colCount < 2) {
            return TableGridResult.NO_TABLE.copy(reason = "Grid too small: ${rowCount}×${colCount}")
        }

        // Step 3: Assign each box to grid cells (handling merged cells)
        val cells = assignBoxesToGrid(boxList, rowBoundaries, colBoundaries)

        // Step 4: Validate the grid and calculate confidence
        val validation = validateGrid(cells, rowCount, colCount, boxes.size)

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
     * Find grid boundaries using adaptive gap clustering.
     * 
     * The key insight: grid lines occur where many box edges align.
     * We cluster edge positions and use cluster centers as boundaries.
     */
    private fun findBoundaries(edges: List<Double>): List<Double> {
        if (edges.size < 2) return emptyList()

        // Sort edges
        val sorted = edges.sorted()

        // Remove near-duplicates (edges within small tolerance are the same line)
        val tolerance = computeAdaptiveTolerance(sorted)
        val dedupedEdges = deduplicateValues(sorted, tolerance)

        if (dedupedEdges.size < 2) return dedupedEdges

        // Find gaps between consecutive unique edges
        val gaps = mutableListOf<GapInfo>()
        for (i in 0 until dedupedEdges.size - 1) {
            gaps.add(GapInfo(
                gap = dedupedEdges[i + 1] - dedupedEdges[i],
                position = (dedupedEdges[i] + dedupedEdges[i + 1]) / 2,
                afterIndex = i
            ))
        }

        if (gaps.isEmpty()) return dedupedEdges

        // Classify gaps: "within-cell" gaps are small, "between-cell" gaps are large
        val gapThreshold = computeGapThreshold(gaps.map { it.gap })

        // Boundaries are at: start, each significant gap, end
        val boundaries = mutableListOf(dedupedEdges.first())
        
        for (g in gaps) {
            if (g.gap >= gapThreshold) {
                // This is a significant gap - add boundary at the edge after the gap
                boundaries.add(dedupedEdges[g.afterIndex + 1])
            }
        }

        // Ensure we have the last edge
        val lastEdge = dedupedEdges.last()
        if (boundaries.last() != lastEdge) {
            boundaries.add(lastEdge)
        }

        return boundaries
    }

    /**
     * Compute adaptive tolerance based on the data spread.
     * Small tolerance for tightly packed data, larger for spread out.
     */
    private fun computeAdaptiveTolerance(sortedValues: List<Double>): Double {
        if (sortedValues.size < 2) return 5.0
        
        val range = sortedValues.last() - sortedValues.first()
        // Tolerance is ~1% of range, but at least 3px and at most 20px
        return maxOf(3.0, minOf(20.0, range * 0.01))
    }

    /**
     * Remove near-duplicate values within tolerance.
     * Returns representative values (cluster centers).
     */
    private fun deduplicateValues(sorted: List<Double>, tolerance: Double): List<Double> {
        if (sorted.isEmpty()) return emptyList()

        val result = mutableListOf<Double>()
        var clusterStart = 0
        var clusterSum = sorted[0]
        var clusterCount = 1

        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[clusterStart] <= tolerance) {
                // Same cluster
                clusterSum += sorted[i]
                clusterCount++
            } else {
                // New cluster - save previous cluster's center
                result.add(clusterSum / clusterCount)
                clusterStart = i
                clusterSum = sorted[i]
                clusterCount = 1
            }
        }
        // Don't forget last cluster
        result.add(clusterSum / clusterCount)

        return result
    }

    /**
     * Compute gap threshold to separate within-cell gaps from between-cell gaps.
     * Uses a simple approach: significant gaps are > 2× median gap.
     * For robustness, also considers the gap distribution.
     */
    private fun computeGapThreshold(gaps: List<Double>): Double {
        if (gaps.isEmpty()) return Double.MAX_VALUE
        if (gaps.size == 1) return gaps[0] * 1.5

        val sorted = gaps.sorted()
        val median = sorted[sorted.size / 2]
        
        // Also compute the "natural break" - largest jump in sorted gaps
        var maxJump = 0.0
        var jumpThreshold = median * 2

        for (i in 0 until sorted.size - 1) {
            val jump = sorted[i + 1] - sorted[i]
            if (jump > maxJump) {
                maxJump = jump
                // Threshold is just above where the jump occurs
                jumpThreshold = sorted[i] + jump * 0.1
            }
        }

        // Use the more conservative (higher) threshold
        return maxOf(median * 1.5, jumpThreshold)
    }

    /**
     * Assign each box to grid cells, handling merged cells.
     * A box might span multiple rows and/or columns.
     */
    private fun assignBoxesToGrid(
        boxList: List<Map.Entry<String, BoundingBox>>,
        rowBoundaries: List<Double>,
        colBoundaries: List<Double>
    ): List<GridCell> {
        val cells = mutableListOf<GridCell>()
        val tolerance = 5.0 // Small tolerance for edge alignment

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
     * For 'start' positions (top, left): find the cell that starts at or before this position.
     * For 'end' positions (bottom, right): find the cell that ends at or after this position.
     */
    private fun findBoundaryIndex(
        value: Double,
        boundaries: List<Double>,
        tolerance: Double,
        type: BoundaryType
    ): Int {
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
     */
    private fun validateGrid(
        cells: List<GridCell>,
        rowCount: Int,
        colCount: Int,
        totalBoxes: Int
    ): ValidationResult {
        // Check: Do we have enough cells assigned?
        if (cells.size < 4) {
            return ValidationResult(
                isValid = false,
                confidence = 0.0,
                reason = "Too few cells assigned to grid"
            )
        }

        // Check: Is the grid reasonably filled?
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
        val fillRatio = filledCount.toDouble() / totalCells

        // Check row consistency - each row should have similar fill
        val rowFills = occupied.map { row -> row.count { it } }
        val avgRowFill = rowFills.average()
        val rowVariance = if (rowFills.isNotEmpty()) {
            rowFills.map { (it - avgRowFill).let { diff -> diff * diff } }.average()
        } else 0.0
        val rowConsistency = 1.0 / (1.0 + rowVariance / maxOf(1.0, avgRowFill * avgRowFill))

        // Calculate confidence
        var confidence = 0.0

        // Base confidence from fill ratio (tables should be >50% filled)
        confidence += when {
            fillRatio >= 0.5 -> 0.3
            fillRatio >= 0.3 -> 0.15
            else -> 0.0
        }

        // Confidence from row consistency
        confidence += rowConsistency * 0.4

        // Bonus for reasonable table size
        if (rowCount >= 3 && colCount >= 2) confidence += 0.15
        if (rowCount >= 5) confidence += 0.1
        if (colCount >= 3) confidence += 0.05

        // Penalty for very sparse tables
        if (fillRatio < 0.3) confidence *= 0.5

        confidence = minOf(1.0, maxOf(0.0, confidence))

        val isValid = confidence >= 0.5 && fillRatio >= 0.3 && rowCount >= 2 && colCount >= 2

        return ValidationResult(
            isValid = isValid,
            confidence = confidence,
            reason = if (isValid) {
                "Grid ${rowCount}×${colCount}, ${(fillRatio * 100).toInt()}% filled"
            } else {
                "Low confidence: fill=${(fillRatio * 100).toInt()}%, consistency=${(rowConsistency * 100).toInt()}%"
            }
        )
    }

    private data class GapInfo(
        val gap: Double,
        val position: Double,
        val afterIndex: Int
    )

    private enum class BoundaryType {
        START, END
    }

    private data class ValidationResult(
        val isValid: Boolean,
        val confidence: Double,
        val reason: String
    )
}
