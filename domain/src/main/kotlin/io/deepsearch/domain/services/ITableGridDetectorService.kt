package io.deepsearch.domain.services

/**
 * Bounding box representing an element's position on the page.
 */
data class TableDetectionBoundingBox(
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
 * Service for detecting table grid structures from bounding box data.
 * 
 * Uses spatial analysis to identify tabular structures without relying on HTML semantics.
 * This enables detection of tables in hidden containers (accordions, collapsed sections)
 * where the visual structure may not match the DOM structure.
 */
interface ITableGridDetectorService {
    /**
     * Detect if the given bounding boxes form a table grid.
     * 
     * @param boxes Map of element ID to bounding box
     * @return Detection result with grid structure if found
     */
    fun detectTable(boxes: Map<String, TableDetectionBoundingBox>): TableGridResult
}
