package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Experiment test to evaluate spatial graph transformation for table identification.
 * 
 * Instead of sending raw HTML to the LLM, we:
 * 1. Pre-compute spatial relationships (alignment rails, grid patterns)
 * 2. Transform to a structured spatial graph representation
 * 3. Send this enriched representation to the LLM
 * 
 * This should make table root identification much more accurate by making
 * row/column relationships explicit.
 */
class SpatialTableIdentificationExperimentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()
    private val client by inject<Client>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ==================== Data Classes ====================

    data class AlignmentRail(
        val id: Int,
        val position: Double,
        val orientation: Orientation,
        val memberIds: List<String>
    )

    enum class Orientation { VERTICAL, HORIZONTAL }

    data class SpatialNode(
        val id: String,
        val tag: String,
        val bbox: IBrowserPage.BoundingBox,
        val content: String?,
        val verticalRailId: Int?,
        val horizontalRailId: Int?,
        val siblingCount: Int,
        val siblingsWithSameStructure: Int,
        val structureSignature: String,
        val childCount: Int,
        val depth: Int
    )

    data class GridCandidate(
        val containerId: String,
        val rows: Int,
        val cols: Int,
        val cellIds: List<String>,
        val confidence: Double,
        val contentHint: String = ""  // Text snippets from cells to help identify table content
    )

    @Serializable
    data class LlmTableResult(
        val id: String,
        val is_table: Boolean = true,
        val type: String = "unknown",
        val reason: String = ""
    )

    @Serializable
    data class TableIdentificationResponse(
        val tables: List<LlmTableResult>
    )

    // ==================== Test ====================

    @Test
    fun `test spatial graph transformation for table identification`() = runTest(testCoroutineDispatcher, timeout = 180.seconds) {
        val url = "https://sleekflow.io/pricing"

        browserPool.withPage { page ->
            println("\n" + "=".repeat(80))
            println("SPATIAL TABLE IDENTIFICATION EXPERIMENT: $url")
            println("=".repeat(80))

            page.navigate(url)
            page.waitForLoad()
            
            // IMPORTANT: Inject stable IDs before capturing snapshot
            // This adds data-ds-id attributes to all elements
            val injectionResult = page.injectStableIds()
            println("Injected stable IDs: ${injectionResult.elements} elements, ${injectionResult.icons} icons, ${injectionResult.images} images")

            // Capture page snapshot (HTML + bounding boxes) after ID injection
            val pageSnapshot = page.capturePageSnapshot()
            val html = pageSnapshot.html
            var boundingBoxes = pageSnapshot.boundingBoxes
            println("Snapshot bounding boxes: ${boundingBoxes.size} elements")
            
            println()
            println("HTML size: ${html.length} chars")
            println("Bounding boxes mapped: ${boundingBoxes.size} elements")
            
            var doc = Jsoup.parse(html)
            var elementsWithId = doc.select("[data-ds-id]")
            println("Elements with data-ds-id in HTML: ${elementsWithId.size}")
            
            // If no data-ds-id found in HTML, something went wrong with injection
            // Fall back to manual injection for this experiment
            if (elementsWithId.isEmpty()) {
                println("WARNING: No data-ds-id found in HTML after injection!")
                println("Manually injecting stable IDs for experiment...")
                var counter = 0
                doc.select("*").forEach { element ->
                    if (!element.hasAttr("data-ds-id")) {
                        element.attr("data-ds-id", "ds-element-${counter++}")
                    }
                }
                elementsWithId = doc.select("[data-ds-id]")
                println("Injected IDs into ${elementsWithId.size} elements")
                // Clear bounding boxes since they don't match our manual IDs
                boundingBoxes = emptyMap()
            }
            println()

            // Since bounding boxes may be empty from remote browser, use DOM-structure-based approach
            val useDomStructureOnly = boundingBoxes.isEmpty()
            
            val (verticalRails, horizontalRails) = if (!useDomStructureOnly) {
                // Step 1: Compute alignment rails from bounding boxes
                println(">>> STEP 1: Computing alignment rails from bounding boxes...")
                computeAlignmentRails(boundingBoxes)
            } else {
                println(">>> STEP 1: Skipping spatial rails (no bounding boxes available)")
                emptyList<AlignmentRail>() to emptyList()
            }
            
            println("Found ${verticalRails.size} vertical rails (columns)")
            println("Found ${horizontalRails.size} horizontal rails (rows)")

            if (verticalRails.isNotEmpty()) {
                println("\nTop 10 vertical rails (by member count):")
                verticalRails.sortedByDescending { it.memberIds.size }.take(10).forEach { rail ->
                    println("  v-rail:${rail.id} at x=${rail.position.toInt()} with ${rail.memberIds.size} members")
                }
            }

            if (horizontalRails.isNotEmpty()) {
                println("\nTop 10 horizontal rails (by member count):")
                horizontalRails.sortedByDescending { it.memberIds.size }.take(10).forEach { rail ->
                    println("  h-rail:${rail.id} at y=${rail.position.toInt()} with ${rail.memberIds.size} members")
                }
            }

            // Step 2: Detect grid patterns (uses DOM structure when no bounding boxes)
            println("\n>>> STEP 2: Detecting structural patterns...")
            val gridCandidates = if (!useDomStructureOnly) {
                detectGridPatterns(boundingBoxes, verticalRails, horizontalRails, doc)
            } else {
                // Use DOM-based detection
                detectGridPatternsFromDom(doc)
            }
            println("Found ${gridCandidates.size} grid candidates")

            gridCandidates.sortedByDescending { it.confidence }.take(10).forEach { grid ->
                println("  ${grid.containerId}: ${grid.rows}×${grid.cols} grid, confidence=${String.format("%.2f", grid.confidence)}")
            }

            // Step 3: Build VISUAL GRID format for LLM
            println("\n>>> STEP 3: Building visual grid format...")
            val visualGridFormat = buildVisualGridFormat(
                candidates = gridCandidates,
                boundingBoxes = boundingBoxes,
                doc = doc
            )
            println("Visual grid format size: ${visualGridFormat.length} chars")
            println()

            // Print the visual grid format
            println(">>> VISUAL GRID FORMAT:")
            println(visualGridFormat)
            println()

            // Step 4: Send to LLM
            println(">>> STEP 4: Sending visual grid format to LLM...")
            val (tables, latencyMs, tokenUsage) = identifyTablesWithLlm(visualGridFormat)

            println("\n>>> RESULTS")
            println("Latency: ${latencyMs}ms")
            println("Tokens: prompt=${tokenUsage.first}, output=${tokenUsage.second}, total=${tokenUsage.third}")
            println()

            val actualTables = tables.filter { it.is_table }
            val notTables = tables.filter { !it.is_table }
            
            println("Candidates analyzed: ${tables.size}")
            println("  → Tables: ${actualTables.size}")
            println("  → Not tables: ${notTables.size}")
            println()
            
            println("=== TABLES ===")
            actualTables.forEachIndexed { idx, table ->
                println("  [$idx] ${table.id}")
                println("       type: ${table.type}")
                println("       reason: ${table.reason}")
            }
            
            if (notTables.isNotEmpty()) {
                println()
                println("=== NOT TABLES (filtered out) ===")
                notTables.forEach { table ->
                    println("  ${table.id}: ${table.type} - ${table.reason}")
                }
            }

            // Step 5: Validate against DOM
            println("\n>>> VALIDATION (Tables only)")
            actualTables.forEach { table ->
                val element = doc.select("[data-ds-id=\"${table.id}\"]").firstOrNull()
                if (element != null) {
                    val childCount = element.children().size
                    val tagName = element.tagName()
                    val bbox = boundingBoxes[table.id]
                    val width = bbox?.let { it.right - it.left }?.toInt() ?: 0
                    val height = bbox?.let { it.bottom - it.top }?.toInt() ?: 0
                    println("  ✓ ${table.id}: <$tagName> with $childCount children, ${width}×${height}px → ${table.type}")
                } else {
                    println("  ✗ ${table.id}: NOT FOUND IN DOM")
                }
            }

            println("\n" + "=".repeat(80))
        }
    }

    // ==================== Spatial Analysis ====================

    /**
     * Compute vertical and horizontal alignment rails from bounding boxes.
     */
    private fun computeAlignmentRails(
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        tolerance: Double = 8.0
    ): Pair<List<AlignmentRail>, List<AlignmentRail>> {
        // Filter out tiny elements and very large containers
        val validBoxes = boundingBoxes.filter { (_, box) ->
            val width = box.right - box.left
            val height = box.bottom - box.top
            width > 20 && height > 10 && width < 2000 && height < 5000
        }

        // Cluster left edges → vertical rails (columns)
        val leftEdges = validBoxes.map { (id, box) -> id to box.left }
        val verticalRails = clusterEdges(leftEdges, tolerance, Orientation.VERTICAL)

        // Cluster top edges → horizontal rails (rows)
        val topEdges = validBoxes.map { (id, box) -> id to box.top }
        val horizontalRails = clusterEdges(topEdges, tolerance, Orientation.HORIZONTAL)

        return verticalRails to horizontalRails
    }

    /**
     * Cluster edges by position using sweep-line approach.
     */
    private fun clusterEdges(
        edges: List<Pair<String, Double>>,
        tolerance: Double,
        orientation: Orientation
    ): List<AlignmentRail> {
        if (edges.isEmpty()) return emptyList()

        val sorted = edges.sortedBy { it.second }
        val clusters = mutableListOf<MutableList<Pair<String, Double>>>()
        var currentCluster = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val (id, position) = sorted[i]
            val clusterCenter = currentCluster.map { it.second }.average()

            if (abs(position - clusterCenter) <= tolerance) {
                currentCluster.add(id to position)
            } else {
                if (currentCluster.size >= 2) {
                    clusters.add(currentCluster)
                }
                currentCluster = mutableListOf(id to position)
            }
        }
        if (currentCluster.size >= 2) {
            clusters.add(currentCluster)
        }

        return clusters.mapIndexed { index, members ->
            AlignmentRail(
                id = index + 1,
                position = members.map { it.second }.average(),
                orientation = orientation,
                memberIds = members.map { it.first }
            )
        }
    }

    /**
     * Detect grid patterns by finding elements at intersections of H and V rails.
     */
    private fun detectGridPatterns(
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        verticalRails: List<AlignmentRail>,
        horizontalRails: List<AlignmentRail>,
        doc: Document
    ): List<GridCandidate> {
        val candidates = mutableListOf<GridCandidate>()

        // Build lookup: elementId -> (vRailId, hRailId)
        val elementToRails = mutableMapOf<String, Pair<Int?, Int?>>()
        for (rail in verticalRails) {
            for (id in rail.memberIds) {
                val existing = elementToRails[id]
                elementToRails[id] = (rail.id to existing?.second)
            }
        }
        for (rail in horizontalRails) {
            for (id in rail.memberIds) {
                val existing = elementToRails[id]
                elementToRails[id] = (existing?.first to rail.id)
            }
        }

        // Find elements that are on BOTH a vertical and horizontal rail (grid cells)
        val gridCells = elementToRails.filter { (_, rails) ->
            rails.first != null && rails.second != null
        }

        // Group grid cells by their parent element
        val cellsByParent = mutableMapOf<String, MutableList<String>>()
        for ((cellId, _) in gridCells) {
            val element = doc.select("[data-ds-id=\"$cellId\"]").firstOrNull() ?: continue
            val parent = element.parent() ?: continue
            val parentId = parent.attr("data-ds-id").takeIf { it.isNotEmpty() } ?: continue
            cellsByParent.getOrPut(parentId) { mutableListOf() }.add(cellId)
        }

        // For each parent with multiple grid cells, compute grid dimensions
        for ((parentId, cells) in cellsByParent) {
            if (cells.size < 4) continue // Minimum 2x2 grid

            val cellRails = cells.mapNotNull { elementToRails[it] }
            val uniqueVRails = cellRails.mapNotNull { it.first }.distinct().size
            val uniqueHRails = cellRails.mapNotNull { it.second }.distinct().size

            if (uniqueVRails >= 2 && uniqueHRails >= 2) {
                // Calculate confidence based on how "complete" the grid is
                val expectedCells = uniqueVRails * uniqueHRails
                val actualCells = cells.size
                val completeness = actualCells.toDouble() / expectedCells

                candidates.add(GridCandidate(
                    containerId = parentId,
                    rows = uniqueHRails,
                    cols = uniqueVRails,
                    cellIds = cells,
                    confidence = completeness.coerceIn(0.0, 1.0)
                ))
            }
        }

        // Also check grandparents (for deeply nested structures)
        val cellsByGrandparent = mutableMapOf<String, MutableList<String>>()
        for ((cellId, _) in gridCells) {
            val element = doc.select("[data-ds-id=\"$cellId\"]").firstOrNull() ?: continue
            val grandparent = element.parent()?.parent() ?: continue
            val grandparentId = grandparent.attr("data-ds-id").takeIf { it.isNotEmpty() } ?: continue
            cellsByGrandparent.getOrPut(grandparentId) { mutableListOf() }.add(cellId)
        }

        for ((grandparentId, cells) in cellsByGrandparent) {
            if (cells.size < 6) continue // Higher threshold for grandparent
            if (candidates.any { it.containerId == grandparentId }) continue // Already found

            val cellRails = cells.mapNotNull { elementToRails[it] }
            val uniqueVRails = cellRails.mapNotNull { it.first }.distinct().size
            val uniqueHRails = cellRails.mapNotNull { it.second }.distinct().size

            if (uniqueVRails >= 2 && uniqueHRails >= 3) {
                val expectedCells = uniqueVRails * uniqueHRails
                val actualCells = cells.size
                val completeness = actualCells.toDouble() / expectedCells

                candidates.add(GridCandidate(
                    containerId = grandparentId,
                    rows = uniqueHRails,
                    cols = uniqueVRails,
                    cellIds = cells,
                    confidence = completeness.coerceIn(0.0, 1.0) * 0.9 // Slight penalty for grandparent
                ))
            }
        }

        return candidates.sortedByDescending { it.confidence }
    }

    // ==================== DOM-based Grid Detection ====================

    companion object {
        // Tags that should NOT be considered as table containers
        private val EXCLUDED_TAGS = setOf(
            "script", "style", "noscript", "template", "head", "meta", "link",
            "nav", "header", "footer", "aside", "section",  // Semantic layout elements (section too generic)
            "ul", "ol", "li",  // Lists (not tables)
            "details", "summary",  // Accordion elements
            "select", "option", "optgroup",  // Form elements
            "svg", "g", "path", "rect", "circle", "line", "polygon", "polyline", "defs", "use",  // SVG elements
            "form", "fieldset", "legend",  // Form containers
            "iframe", "object", "embed",  // Embedded content
            "audio", "video", "source", "track",  // Media elements
            "canvas", "map", "area",  // Other non-table elements
            "button", "a",  // Interactive elements that shouldn't be table roots
            "img", "picture", "figure", "figcaption"  // Media elements
        )
        
        // Tags that ARE allowed as table containers
        private val TABLE_CONTAINER_TAGS = setOf(
            "table", "tbody", "thead", "tfoot",  // Semantic table elements
            "div", "span",  // Generic containers often used for CSS tables
            "article",  // Can contain tables
            "main"  // Main content area
        )
        
        // Tags that are likely to be table rows
        private val ROW_LIKE_TAGS = setOf("tr", "div", "li", "article", "section")
        
        // Tags that are likely to be table cells
        private val CELL_LIKE_TAGS = setOf("td", "th", "div", "span", "p", "a")
    }

    /**
     * Detect grid patterns purely from DOM structure (when bounding boxes aren't available).
     * Uses sibling patterns and child count consistency to identify table structures.
     * 
     * FILTERING: Excludes non-table semantic elements like <ul>, <nav>, <svg>, etc.
     */
    private fun detectGridPatternsFromDom(doc: Document): List<GridCandidate> {
        val candidates = mutableListOf<GridCandidate>()

        // Find elements with multiple children that have consistent structure
        doc.select("[data-ds-id]").forEach { element ->
            val tagName = element.tagName().lowercase()
            
            // Skip excluded tags OR tags not in allowed list
            if (tagName in EXCLUDED_TAGS) return@forEach
            if (tagName !in TABLE_CONTAINER_TAGS) return@forEach
            
            val children = element.children().filter { child ->
                child.hasAttr("data-ds-id") && 
                child.tagName().lowercase() !in EXCLUDED_TAGS
            }
            if (children.size < 2) return@forEach

            // Check if children have consistent structure (same tag + similar child count)
            val signatures = children.map { child ->
                "${child.tagName()}:${child.children().size}"
            }
            val dominantSignature = signatures.groupingBy { it }.eachCount().maxByOrNull { it.value }

            if (dominantSignature != null && dominantSignature.value >= 2) {
                val consistentChildren = children.filter { child ->
                    "${child.tagName()}:${child.children().size}" == dominantSignature.key
                }

                if (consistentChildren.size >= 2) {
                    // This looks like rows - check if grandchildren also have consistent structure
                    val grandchildCounts = consistentChildren.map { it.children().size }
                    val avgGrandchildren = grandchildCounts.average()
                    val variance = grandchildCounts.map { (it - avgGrandchildren) * (it - avgGrandchildren) }.average()

                    // Low variance in grandchild count suggests a grid
                    if (avgGrandchildren >= 2 && variance < 2.0) {
                        val rows = consistentChildren.size
                        val cols = avgGrandchildren.toInt()
                        val cellIds = consistentChildren.flatMap { row ->
                            row.children().filter { it.hasAttr("data-ds-id") }.map { it.attr("data-ds-id") }
                        }

                        val containerId = element.attr("data-ds-id")
                        if (containerId.isNotEmpty()) {
                            // Extract content hints from first few cells
                            val contentHints = extractContentHints(consistentChildren)
                            
                            candidates.add(GridCandidate(
                                containerId = containerId,
                                rows = rows,
                                cols = cols,
                                cellIds = cellIds,
                                confidence = 0.8 * (consistentChildren.size.toDouble() / children.size),
                                contentHint = contentHints
                            ))
                        }
                    }
                }
            }
        }

        // Merge nested grids and deduplicate
        val merged = mergeNestedGridCandidates(candidates, doc)
        
        return merged.sortedByDescending { it.confidence }
            .take(20)
    }
    
    /**
     * Extract content hints from grid rows to help LLM understand what kind of data this is.
     */
    private fun extractContentHints(rows: List<Element>): String {
        val hints = mutableListOf<String>()
        
        // Get text from first row (likely header)
        val firstRowText = rows.firstOrNull()?.children()
            ?.take(4)
            ?.mapNotNull { cell -> 
                cell.text().take(20).trim().takeIf { it.isNotBlank() } 
            }
            ?.joinToString(" | ")
        
        if (!firstRowText.isNullOrBlank()) {
            hints.add("Header: $firstRowText")
        }
        
        // Get text from second row (likely first data row)
        if (rows.size > 1) {
            val secondRowText = rows[1].children()
                ?.take(4)
                ?.mapNotNull { cell -> 
                    cell.text().take(20).trim().takeIf { it.isNotBlank() } 
                }
                ?.joinToString(" | ")
            
            if (!secondRowText.isNullOrBlank()) {
                hints.add("Row1: $secondRowText")
            }
        }
        
        return hints.joinToString("; ")
    }
    
    /**
     * Merge nested grid candidates - if a grid is inside another grid, keep only the outermost.
     * Exception: If inner grid has significantly different structure, keep both.
     */
    private fun mergeNestedGridCandidates(
        candidates: List<GridCandidate>,
        doc: Document
    ): List<GridCandidate> {
        if (candidates.size <= 1) return candidates
        
        val result = mutableListOf<GridCandidate>()
        val processedIds = mutableSetOf<String>()
        
        // Sort by confidence descending, then by cell count descending (prefer larger grids)
        val sorted = candidates.sortedWith(
            compareByDescending<GridCandidate> { it.confidence }
                .thenByDescending { it.cellIds.size }
        )
        
        for (candidate in sorted) {
            if (candidate.containerId in processedIds) continue
            
            val element = doc.select("[data-ds-id=\"${candidate.containerId}\"]").firstOrNull()
                ?: continue
            
            // Check if this candidate is a descendant of any already-added candidate
            val isNestedInExisting = result.any { existing ->
                val existingElement = doc.select("[data-ds-id=\"${existing.containerId}\"]").firstOrNull()
                existingElement?.let { element.parents().contains(it) } ?: false
            }
            
            // Check if this candidate contains any already-added candidate
            val containsExisting = result.any { existing ->
                val existingElement = doc.select("[data-ds-id=\"${existing.containerId}\"]").firstOrNull()
                existingElement?.parents()?.contains(element) ?: false
            }
            
            if (!isNestedInExisting) {
                // If this contains an existing grid, we might want to replace or keep both
                if (containsExisting) {
                    // Only add if significantly different structure (different dimensions)
                    val existingInside = result.filter { existing ->
                        val existingElement = doc.select("[data-ds-id=\"${existing.containerId}\"]").firstOrNull()
                        existingElement?.parents()?.contains(element) ?: false
                    }
                    val isDifferentStructure = existingInside.all { existing ->
                        candidate.rows != existing.rows || candidate.cols != existing.cols
                    }
                    if (isDifferentStructure && candidate.confidence >= 0.6) {
                        result.add(candidate)
                        processedIds.add(candidate.containerId)
                    }
                } else {
                    result.add(candidate)
                    processedIds.add(candidate.containerId)
                }
            }
        }
        
        return result
    }

    // ==================== Structural Graph Building ====================

    /**
     * Build structural graph representation for LLM consumption.
     * Works without bounding boxes, using DOM structure analysis.
     */
    private fun buildStructuralGraph(
        doc: Document,
        gridCandidates: List<GridCandidate>
    ): String {
        val sb = StringBuilder()

        val gridContainerIds = gridCandidates.map { it.containerId }.toSet()
        val gridByContainer = gridCandidates.associateBy { it.containerId }

        // Header
        sb.appendLine("=== STRUCTURAL ELEMENT GRAPH FOR TABLE IDENTIFICATION ===")
        sb.appendLine()
        sb.appendLine("LEGEND:")
        sb.appendLine("  ★GRID(RxC)★ = pre-detected grid pattern with R rows and C columns")
        sb.appendLine("  [N siblings, M same] = N total siblings, M with identical structure")
        sb.appendLine("  LIKELY_ROW = element has many siblings with same structure (probably a table row)")
        sb.appendLine()

        // Pre-detected grids summary with content hints
        sb.appendLine("=== PRE-DETECTED GRID PATTERNS (high confidence table roots) ===")
        if (gridCandidates.isEmpty()) {
            sb.appendLine("  (none detected)")
        } else {
            gridCandidates.take(15).forEach { grid ->
                sb.append("  ${grid.containerId}: ${grid.rows}×${grid.cols} grid")
                sb.append(" (confidence: ${String.format("%.0f%%", grid.confidence * 100)})")
                if (grid.contentHint.isNotBlank()) {
                    sb.append(" → ${grid.contentHint.take(80)}")
                }
                sb.appendLine()
            }
        }
        sb.appendLine()

        // Sibling pattern analysis with content hints
        sb.appendLine("=== SIBLING PATTERNS (row indicators) ===")
        val rowPatterns = findRowPatterns(doc)
        if (rowPatterns.isEmpty()) {
            sb.appendLine("  (no significant row patterns detected)")
        } else {
            rowPatterns.take(20).forEach { pattern ->
                sb.append("  ${pattern.elementId}: ${pattern.siblingCount} siblings, ${pattern.sameStructureCount} identical")
                sb.append(" → parent: ${pattern.parentId}")
                if (pattern.contentHint.isNotBlank()) {
                    sb.append(" → \"${pattern.contentHint.take(50)}\"")
                }
                sb.appendLine()
            }
        }
        sb.appendLine()

        // Element hierarchy (pruned to table-relevant)
        sb.appendLine("=== ELEMENT HIERARCHY (pruned to table-relevant) ===")
        renderDomHierarchy(doc, sb, gridContainerIds, gridByContainer, rowPatterns.map { it.elementId }.toSet())

        return sb.toString()
    }

    // ==================== Visual Grid Format (New Approach) ====================

    /**
     * Build a visual grid format that shows actual table content in ASCII art.
     * This format is much more intuitive for LLM reasoning.
     */
    private fun buildVisualGridFormat(
        candidates: List<GridCandidate>,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        doc: Document
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== PAGE TABLE ANALYSIS ===")
        sb.appendLine()
        
        if (candidates.isEmpty()) {
            sb.appendLine("No table candidates detected.")
            return sb.toString()
        }

        // Sort candidates by position (top to bottom, left to right)
        val sortedCandidates = candidates.sortedWith(
            compareBy(
                { boundingBoxes[it.containerId]?.top ?: 0.0 },
                { boundingBoxes[it.containerId]?.left ?: 0.0 }
            )
        )

        for ((index, candidate) in sortedCandidates.withIndex()) {
            val element = doc.select("[data-ds-id='${candidate.containerId}']").firstOrNull()
                ?: continue
            
            val bbox = boundingBoxes[candidate.containerId]
            
            sb.appendLine("─".repeat(70))
            sb.appendLine("CANDIDATE ${index + 1}: ${candidate.containerId}")
            sb.appendLine("─".repeat(70))
            
            // Basic info
            sb.appendLine("Tag: <${element.tagName()}>")
            if (bbox != null) {
                val width = (bbox.right - bbox.left).toInt()
                val height = (bbox.bottom - bbox.top).toInt()
                sb.appendLine("Position: (${bbox.left.toInt()}, ${bbox.top.toInt()}) → (${bbox.right.toInt()}, ${bbox.bottom.toInt()})  [${width}×${height} px]")
            }
            sb.appendLine("Structure: ${candidate.rows} rows × ${candidate.cols} columns")
            sb.appendLine("Detection confidence: ${String.format("%.0f%%", candidate.confidence * 100)}")
            sb.appendLine()
            
            // Render the visual grid
            sb.appendLine("CONTENT PREVIEW:")
            renderAsciiTable(sb, element, candidate.rows, candidate.cols)
            sb.appendLine()
        }

        // Nesting analysis
        sb.appendLine("─".repeat(70))
        sb.appendLine("NESTING ANALYSIS:")
        sb.appendLine("─".repeat(70))
        renderNestingAnalysis(sb, sortedCandidates, boundingBoxes, doc)
        
        return sb.toString()
    }

    /**
     * Render an ASCII table representation of the element's content.
     */
    private fun renderAsciiTable(
        sb: StringBuilder,
        element: Element,
        maxRows: Int,
        maxCols: Int
    ) {
        // Get row elements (direct children that look like rows)
        val rowElements = element.children()
            .filter { child -> 
                // Skip non-content elements
                val tag = child.tagName().lowercase()
                tag !in setOf("style", "script", "template")
            }
            .take(maxRows.coerceAtMost(8))  // Limit to 8 rows for readability
        
        if (rowElements.isEmpty()) {
            sb.appendLine("    (no row content found)")
            return
        }

        // Extract cell content for each row
        val tableData = mutableListOf<List<String>>()
        for (row in rowElements) {
            val cells = extractCellContent(row, maxCols)
            if (cells.isNotEmpty()) {
                tableData.add(cells)
            }
        }

        if (tableData.isEmpty()) {
            sb.appendLine("    (could not extract cell content)")
            return
        }

        // Calculate column widths
        val numCols = tableData.maxOfOrNull { it.size } ?: 0
        val colWidths = (0 until numCols).map { colIdx ->
            tableData.mapNotNull { row -> row.getOrNull(colIdx)?.length }.maxOrNull() ?: 0
        }.map { it.coerceIn(5, 18) }  // Min 5, max 18 chars per column

        // Render ASCII table
        val totalCols = colWidths.size.coerceAtMost(6)  // Limit to 6 columns for readability
        val displayWidths = colWidths.take(totalCols)
        
        // Top border
        sb.append("    ┌")
        sb.append(displayWidths.joinToString("┬") { "─".repeat(it + 2) })
        if (colWidths.size > totalCols) sb.append("┬───")
        sb.appendLine("┐")

        for ((rowIdx, row) in tableData.withIndex()) {
            // Cell content
            sb.append("    │")
            for (colIdx in 0 until totalCols) {
                val cell = row.getOrNull(colIdx) ?: ""
                val width = displayWidths[colIdx]
                val truncated = if (cell.length > width) cell.take(width - 1) + "…" else cell
                sb.append(" ${truncated.padEnd(width)} │")
            }
            if (colWidths.size > totalCols) {
                sb.append(" … │")
            }
            sb.appendLine()

            // Separator after first row (header)
            if (rowIdx == 0 && tableData.size > 1) {
                sb.append("    ├")
                sb.append(displayWidths.joinToString("┼") { "─".repeat(it + 2) })
                if (colWidths.size > totalCols) sb.append("┼───")
                sb.appendLine("┤")
            }
        }

        // Bottom border
        sb.append("    └")
        sb.append(displayWidths.joinToString("┴") { "─".repeat(it + 2) })
        if (colWidths.size > totalCols) sb.append("┴───")
        sb.appendLine("┘")

        // Show if truncated
        if (tableData.size < maxRows) {
            // All rows shown
        } else {
            sb.appendLine("    ... (${maxRows - tableData.size} more rows)")
        }
    }

    /**
     * Extract cell content from a row element.
     * Handles various table structures (semantic tables, div grids, etc.)
     */
    private fun extractCellContent(row: Element, maxCols: Int): List<String> {
        val cells = mutableListOf<String>()
        
        // Try to find cell-like children
        val cellElements = row.children()
            .filter { child ->
                val tag = child.tagName().lowercase()
                tag !in setOf("style", "script", "template", "svg", "img")
            }
            .take(maxCols)

        if (cellElements.isEmpty()) {
            // No children - use the row's own text
            val text = row.text().take(50).trim()
            if (text.isNotBlank()) {
                cells.add(text)
            }
        } else {
            for (cell in cellElements) {
                val text = cell.text().trim()
                val displayText = when {
                    text.isBlank() -> {
                        // Check for checkmark icons
                        if (cell.select("svg, img, [class*=check], [class*=tick]").isNotEmpty()) "✓"
                        else if (cell.select("[class*=cross], [class*=close]").isNotEmpty()) "✗"
                        else "—"
                    }
                    text.length > 20 -> text.take(17) + "..."
                    else -> text
                }
                cells.add(displayText)
            }
        }

        return cells
    }

    /**
     * Analyze and display nesting relationships between candidates.
     */
    private fun renderNestingAnalysis(
        sb: StringBuilder,
        candidates: List<GridCandidate>,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        doc: Document
    ) {
        if (candidates.size <= 1) {
            sb.appendLine("  No nesting detected (single candidate)")
            return
        }

        val nestingRelations = mutableListOf<String>()
        
        for (outer in candidates) {
            val outerElement = doc.select("[data-ds-id='${outer.containerId}']").firstOrNull()
                ?: continue
            
            for (inner in candidates) {
                if (outer.containerId == inner.containerId) continue
                
                val innerElement = doc.select("[data-ds-id='${inner.containerId}']").firstOrNull()
                    ?: continue
                
                // Check if inner is a descendant of outer
                if (innerElement.parents().contains(outerElement)) {
                    nestingRelations.add("  ${outer.containerId} CONTAINS ${inner.containerId}")
                }
            }
        }

        if (nestingRelations.isEmpty()) {
            sb.appendLine("  No nesting detected (all candidates are independent)")
        } else {
            nestingRelations.forEach { sb.appendLine(it) }
        }
    }

    data class RowPattern(
        val elementId: String,
        val parentId: String,
        val siblingCount: Int,
        val sameStructureCount: Int,
        val structureSignature: String,
        val contentHint: String = ""
    )

    private fun findRowPatterns(doc: Document): List<RowPattern> {
        val patterns = mutableListOf<RowPattern>()

        doc.select("[data-ds-id]").forEach { element ->
            val tagName = element.tagName().lowercase()
            
            // Skip excluded tags or non-table tags
            if (tagName in EXCLUDED_TAGS) return@forEach
            
            val parent = element.parent() ?: return@forEach
            val parentTag = parent.tagName().lowercase()
            
            // Skip if parent is an excluded tag or not a table container
            if (parentTag in EXCLUDED_TAGS) return@forEach
            if (parentTag !in TABLE_CONTAINER_TAGS) return@forEach
            
            val parentId = parent.attr("data-ds-id").takeIf { it.isNotEmpty() } ?: return@forEach

            val siblings = parent.children().filter { child ->
                child.hasAttr("data-ds-id") && 
                child.tagName().lowercase() !in EXCLUDED_TAGS
            }
            if (siblings.size < 3) return@forEach

            val mySignature = "${element.tagName()}[${element.children().size}]"
            val sameStructure = siblings.count { sib ->
                "${sib.tagName()}[${sib.children().size}]" == mySignature
            }

            if (sameStructure >= 3) {
                // Extract content hint from this row
                val contentHint = element.children()
                    .take(3)
                    .mapNotNull { it.text().take(15).trim().takeIf { t -> t.isNotBlank() } }
                    .joinToString(" | ")
                
                patterns.add(RowPattern(
                    elementId = element.attr("data-ds-id"),
                    parentId = parentId,
                    siblingCount = siblings.size,
                    sameStructureCount = sameStructure,
                    structureSignature = mySignature,
                    contentHint = contentHint
                ))
            }
        }

        // Deduplicate - only keep one representative per parent
        return patterns.distinctBy { it.parentId }
            .sortedByDescending { it.sameStructureCount }
    }

    private fun renderDomHierarchy(
        doc: Document,
        sb: StringBuilder,
        gridContainerIds: Set<String>,
        gridByContainer: Map<String, GridCandidate>,
        rowElementIds: Set<String>
    ) {
        val body = doc.body() ?: return

        // Collect relevant element IDs
        val relevantIds = mutableSetOf<String>()
        relevantIds.addAll(gridContainerIds)
        relevantIds.addAll(rowElementIds)

        // Add parents of grid containers and row elements
        (gridContainerIds + rowElementIds).forEach { id ->
            var element = doc.select("[data-ds-id=\"$id\"]").firstOrNull()
            repeat(3) {
                element = element?.parent()
                val parentId = element?.attr("data-ds-id")?.takeIf { it.isNotEmpty() }
                if (parentId != null) relevantIds.add(parentId)
            }
        }

        fun renderElement(element: Element, depth: Int) {
            if (depth > 6) return

            val id = element.attr("data-ds-id").takeIf { it.isNotEmpty() } ?: return

            // Skip if not relevant and not an ancestor of relevant elements
            val hasRelevantDescendant = element.select("[data-ds-id]").any { it.attr("data-ds-id") in relevantIds }
            if (id !in relevantIds && !hasRelevantDescendant) return

            val indent = "  ".repeat(depth)
            val tag = element.tagName().uppercase()

            val annotations = mutableListOf<String>()

            // Grid container marker
            val grid = gridByContainer[id]
            if (grid != null) {
                annotations.add("★GRID(${grid.rows}×${grid.cols})★")
            }

            // Sibling pattern
            val parent = element.parent()
            val siblings = parent?.children()?.filter { it.hasAttr("data-ds-id") } ?: emptyList()
            if (siblings.size > 1) {
                val mySignature = "${element.tagName()}[${element.children().size}]"
                val sameStructure = siblings.count { "${it.tagName()}[${it.children().size}]" == mySignature }
                if (sameStructure >= 3) {
                    annotations.add("[${siblings.size} siblings, $sameStructure same]")
                    annotations.add("LIKELY_ROW")
                }
            }

            // Children count
            val childCount = element.children().filter { it.hasAttr("data-ds-id") }.size
            if (childCount > 0) {
                annotations.add("children=$childCount")
            }

            // Text content (truncated)
            val text = element.ownText().take(25).replace("\n", " ").trim()
            if (text.isNotBlank()) {
                annotations.add("\"$text\"")
            }

            // Render line
            sb.append(indent)
            sb.append("[$id] $tag")
            if (annotations.isNotEmpty()) {
                sb.append(" ─ ${annotations.joinToString(" ─ ")}")
            }
            sb.appendLine()

            // Recurse to children
            element.children().forEach { child ->
                renderElement(child, depth + 1)
            }
        }

        body.children().forEach { child ->
            renderElement(child, 0)
        }
    }

    // ==================== Spatial Graph Building (with bounding boxes) ====================

    /**
     * Build spatial graph representation for LLM consumption.
     */
    private fun buildSpatialGraph(
        html: String,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        verticalRails: List<AlignmentRail>,
        horizontalRails: List<AlignmentRail>,
        gridCandidates: List<GridCandidate>
    ): String {
        val doc = Jsoup.parse(html)
        val sb = StringBuilder()

        // Build lookup maps
        val elementToVRail = mutableMapOf<String, Int>()
        val elementToHRail = mutableMapOf<String, Int>()
        for (rail in verticalRails) {
            for (id in rail.memberIds) {
                elementToVRail[id] = rail.id
            }
        }
        for (rail in horizontalRails) {
            for (id in rail.memberIds) {
                elementToHRail[id] = rail.id
            }
        }

        val gridContainerIds = gridCandidates.map { it.containerId }.toSet()
        val gridByContainer = gridCandidates.associateBy { it.containerId }

        // Header
        sb.appendLine("=== SPATIAL ELEMENT GRAPH FOR TABLE IDENTIFICATION ===")
        sb.appendLine()
        sb.appendLine("LEGEND:")
        sb.appendLine("  v-rail:N = vertical alignment rail (elements on same v-rail are in same COLUMN)")
        sb.appendLine("  h-rail:N = horizontal alignment rail (elements on same h-rail are in same ROW)")
        sb.appendLine("  ★GRID(RxC)★ = pre-detected grid pattern with R rows and C columns")
        sb.appendLine("  [N siblings, M same] = N total siblings, M with identical structure")
        sb.appendLine()

        // Pre-detected grids summary
        sb.appendLine("=== PRE-DETECTED GRID PATTERNS ===")
        if (gridCandidates.isEmpty()) {
            sb.appendLine("  (none detected)")
        } else {
            gridCandidates.take(15).forEach { grid ->
                val bbox = boundingBoxes[grid.containerId]
                val bboxStr = bbox?.let { "(${it.left.toInt()},${it.top.toInt()})→(${it.right.toInt()},${it.bottom.toInt()})" } ?: "?"
                sb.appendLine("  ${grid.containerId}: ${grid.rows}×${grid.cols} grid at $bboxStr (confidence: ${String.format("%.0f%%", grid.confidence * 100)})")
            }
        }
        sb.appendLine()

        // Rail membership summary
        sb.appendLine("=== SIGNIFICANT ALIGNMENT RAILS ===")
        sb.appendLine("Vertical rails (columns) with 5+ members:")
        verticalRails.filter { it.memberIds.size >= 5 }.sortedByDescending { it.memberIds.size }.take(10).forEach { rail ->
            sb.appendLine("  v-rail:${rail.id} at x≈${rail.position.toInt()} → ${rail.memberIds.size} elements")
        }
        sb.appendLine()
        sb.appendLine("Horizontal rails (rows) with 5+ members:")
        horizontalRails.filter { it.memberIds.size >= 5 }.sortedByDescending { it.memberIds.size }.take(15).forEach { rail ->
            sb.appendLine("  h-rail:${rail.id} at y≈${rail.position.toInt()} → ${rail.memberIds.size} elements")
        }
        sb.appendLine()

        // Element hierarchy (pruned)
        sb.appendLine("=== ELEMENT HIERARCHY (pruned to table-relevant elements) ===")

        // Find elements that are likely table-related
        val tableRelevantIds = mutableSetOf<String>()
        tableRelevantIds.addAll(gridContainerIds)
        gridCandidates.forEach { tableRelevantIds.addAll(it.cellIds) }

        // Add elements on multiple rails
        val multiRailElements = boundingBoxes.keys.filter { id ->
            elementToVRail.containsKey(id) && elementToHRail.containsKey(id)
        }
        tableRelevantIds.addAll(multiRailElements)

        // Add parents of grid cells
        gridCandidates.flatMap { it.cellIds }.forEach { cellId ->
            val element = doc.select("[data-ds-id=\"$cellId\"]").firstOrNull()
            var parent = element?.parent()
            repeat(3) {
                val parentId = parent?.attr("data-ds-id")?.takeIf { it.isNotEmpty() }
                if (parentId != null) {
                    tableRelevantIds.add(parentId)
                }
                parent = parent?.parent()
            }
        }

        // Render relevant elements
        renderElementHierarchy(
            doc = doc,
            sb = sb,
            boundingBoxes = boundingBoxes,
            elementToVRail = elementToVRail,
            elementToHRail = elementToHRail,
            gridByContainer = gridByContainer,
            relevantIds = tableRelevantIds,
            maxDepth = 8
        )

        return sb.toString()
    }

    /**
     * Render element hierarchy with spatial annotations.
     */
    private fun renderElementHierarchy(
        doc: Document,
        sb: StringBuilder,
        boundingBoxes: Map<String, IBrowserPage.BoundingBox>,
        elementToVRail: Map<String, Int>,
        elementToHRail: Map<String, Int>,
        gridByContainer: Map<String, GridCandidate>,
        relevantIds: Set<String>,
        maxDepth: Int
    ) {
        val body = doc.body() ?: return

        fun renderElement(element: Element, depth: Int, prefix: String) {
            if (depth > maxDepth) return

            val id = element.attr("data-ds-id").takeIf { it.isNotEmpty() } ?: return
            val bbox = boundingBoxes[id] ?: return

            // Skip if not relevant and not an ancestor of relevant elements
            val hasRelevantDescendant = element.select("[data-ds-id]").any { it.attr("data-ds-id") in relevantIds }
            if (id !in relevantIds && !hasRelevantDescendant) return

            val indent = "  ".repeat(depth)
            val tag = element.tagName().uppercase()

            // Build annotation parts
            val annotations = mutableListOf<String>()

            // Bounding box
            val bboxStr = "(${bbox.left.toInt()},${bbox.top.toInt()})→(${bbox.right.toInt()},${bbox.bottom.toInt()})"
            annotations.add("bbox:$bboxStr")

            // Rail membership
            val vRail = elementToVRail[id]
            val hRail = elementToHRail[id]
            if (vRail != null) annotations.add("v-rail:$vRail")
            if (hRail != null) annotations.add("h-rail:$hRail")

            // Grid container marker
            val grid = gridByContainer[id]
            if (grid != null) {
                annotations.add("★GRID(${grid.rows}×${grid.cols})★")
            }

            // Sibling pattern
            val siblings = element.parent()?.children()?.filter { it.hasAttr("data-ds-id") } ?: emptyList()
            if (siblings.size > 1) {
                val mySignature = "${element.tagName()}[${element.children().size}]"
                val sameStructure = siblings.count { sib ->
                    "${sib.tagName()}[${sib.children().size}]" == mySignature
                }
                if (sameStructure >= 3) {
                    annotations.add("[${siblings.size} siblings, $sameStructure same]")
                }
            }

            // Text content (truncated)
            val text = element.ownText().take(25).replace("\n", " ").trim()
            if (text.isNotBlank()) {
                annotations.add("\"$text\"")
            }

            // Render line
            sb.append(indent)
            sb.append("[$id] $tag")
            if (annotations.isNotEmpty()) {
                sb.append(" ─ ${annotations.joinToString(" ─ ")}")
            }
            sb.appendLine()

            // Mark likely rows
            val siblingPatternMatch = siblings.size >= 3 && 
                siblings.count { "${it.tagName()}[${it.children().size}]" == "${element.tagName()}[${element.children().size}]" } >= 3
            if (siblingPatternMatch && hRail != null) {
                sb.append(indent)
                sb.appendLine("    ↳ LIKELY ROW (shares h-rail with ${siblings.size - 1} similar siblings)")
            }

            // Recurse to children
            element.children().forEach { child ->
                renderElement(child, depth + 1, prefix)
            }
        }

        body.children().forEach { child ->
            renderElement(child, 0, "")
        }
    }

    // ==================== LLM Integration ====================

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Classification of table candidates")
        .properties(mapOf(
            "tables" to Schema.builder()
                .type("ARRAY")
                .items(Schema.builder()
                    .type("OBJECT")
                    .properties(mapOf(
                        "id" to Schema.builder().type("STRING")
                            .description("The data-ds-id of the candidate element")
                            .build(),
                        "is_table" to Schema.builder().type("BOOLEAN")
                            .description("true if this is a data table, false otherwise")
                            .build(),
                        "type" to Schema.builder().type("STRING")
                            .description("Table type: feature_comparison, pricing, schedule, data_list, card_layout, navigation, faq, not_table")
                            .build(),
                        "reason" to Schema.builder().type("STRING")
                            .description("Brief explanation based on the content seen in the preview")
                            .build()
                    ))
                    .required(listOf("id", "is_table", "type", "reason"))
                    .build())
                .build()
        ))
        .required(listOf("tables"))
        .build()

    private val systemInstruction = """
        You are analyzing webpage elements to identify DATA TABLES.
        
        You will see CANDIDATE elements with:
        - Their HTML tag and position (bounding box in pixels)
        - Grid structure (rows × columns)
        - A VISUAL PREVIEW showing actual cell content in ASCII table format
        - Nesting relationships between candidates
        
        YOUR TASK: For each candidate, determine if it is a DATA TABLE.
        
        A DATA TABLE has:
        - Multiple rows of COMPARABLE information (same type of data per column)
        - A consistent grid structure where data can be compared across rows
        - Content that represents structured data (features, prices, schedules, etc.)
        
        NOT A DATA TABLE (exclude these):
        - Card layouts / pricing cards (items displayed side-by-side but not comparable row data)
        - Navigation menus (even if grid-like)
        - FAQ accordions (question-answer pairs, not tabular data)
        - Image galleries or logo grids
        - Feature highlight lists (icon + text items)
        
        NESTED TABLES:
        - If a table CONTAINS another table, report BOTH as separate tables
        - Each should be independently useful as a data table
        
        FOR EACH CANDIDATE, decide:
        - is_table: true if it's a data table, false otherwise
        - type: "feature_comparison", "pricing", "schedule", "data_list", "not_table", etc.
        - reason: Brief explanation based on the CONTENT you see in the preview
        
        Base your decision on the ACTUAL CONTENT shown in the visual preview, not just the structure.
    """.trimIndent()

    /**
     * Send spatial graph to LLM for table identification.
     */
    private suspend fun identifyTablesWithLlm(
        spatialGraph: String
    ): Triple<List<LlmTableResult>, Long, Triple<Int, Int, Int>> {
        var promptTokens = 0
        var outputTokens = 0
        var totalTokens = 0

        val config = GenerateContentConfig.builder()
            .temperature(0f)
            .responseSchema(outputSchema)
            .responseMimeType("application/json")
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .build()

        val contents = listOf(
            Content.builder()
                .role("user")
                .parts(listOf(Part.fromText(spatialGraph)))
                .build()
        )

        val startTime = System.currentTimeMillis()
        val response = client.models.generateContent(
            "gemini-2.5-flash-lite-preview-09-2025",
            contents,
            config
        )
        val latencyMs = System.currentTimeMillis() - startTime

        response.usageMetadata().ifPresent { metadata ->
            promptTokens = metadata.promptTokenCount().orElse(0)
            outputTokens = metadata.candidatesTokenCount().orElse(0)
            totalTokens = metadata.totalTokenCount().orElse(0)
        }

        val responseText = response.text() ?: "{\"tables\":[]}"

        println("\n>>> RAW LLM RESPONSE:")
        println(responseText)

        val parsed = try {
            json.decodeFromString<TableIdentificationResponse>(responseText)
        } catch (e: Exception) {
            println("Failed to parse response: ${e.message}")
            TableIdentificationResponse(emptyList())
        }

        return Triple(
            parsed.tables,
            latencyMs,
            Triple(promptTokens, outputTokens, totalTokens)
        )
    }

    // ==================== Comparison Test ====================

    @Test
    fun `compare spatial vs raw HTML approaches`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        val url = "https://sleekflow.io/pricing"

        browserPool.withPage { page ->
            println("\n" + "=".repeat(80))
            println("COMPARISON: Spatial Graph vs Raw HTML")
            println("=".repeat(80))

            page.navigate(url)
            page.waitForLoad()
            
            // Inject stable IDs first
            page.injectStableIds()

            val pageSnapshot = page.capturePageSnapshot()
            val boundingBoxes = pageSnapshot.boundingBoxes
            val html = pageSnapshot.html

            // Approach 1: Spatial Graph
            println("\n>>> APPROACH 1: Spatial Graph")
            val (verticalRails, horizontalRails) = computeAlignmentRails(boundingBoxes)
            val doc = Jsoup.parse(html)
            val gridCandidates = detectGridPatterns(boundingBoxes, verticalRails, horizontalRails, doc)

            val spatialGraph = buildSpatialGraph(
                html = html,
                boundingBoxes = boundingBoxes,
                verticalRails = verticalRails,
                horizontalRails = horizontalRails,
                gridCandidates = gridCandidates
            )

            val (spatialTables, spatialLatency, spatialTokens) = identifyTablesWithLlm(spatialGraph)

            println("Spatial approach found ${spatialTables.size} tables in ${spatialLatency}ms")
            println("Tokens: ${spatialTokens.third}")
            spatialTables.forEach { println("  - ${it.id}: ${it.type} - ${it.reason}") }

            // Approach 2: Raw HTML (simplified/cleaned)
            println("\n>>> APPROACH 2: Raw HTML (cleaned)")
            val cleanedHtml = cleanHtmlForComparison(html)
            val (htmlTables, htmlLatency, htmlTokens) = identifyTablesWithRawHtml(cleanedHtml)

            println("Raw HTML approach found ${htmlTables.size} tables in ${htmlLatency}ms")
            println("Tokens: ${htmlTokens.third}")
            htmlTables.forEach { println("  - ${it.id}: ${it.type} - ${it.reason}") }

            // Summary
            println("\n>>> COMPARISON SUMMARY")
            println("┌─────────────────────┬──────────────────┬──────────────────┐")
            println("│ Metric              │ Spatial Graph    │ Raw HTML         │")
            println("├─────────────────────┼──────────────────┼──────────────────┤")
            println("│ Tables found        │ ${spatialTables.size.toString().padEnd(16)} │ ${htmlTables.size.toString().padEnd(16)} │")
            println("│ Latency (ms)        │ ${spatialLatency.toString().padEnd(16)} │ ${htmlLatency.toString().padEnd(16)} │")
            println("│ Input tokens        │ ${spatialTokens.first.toString().padEnd(16)} │ ${htmlTokens.first.toString().padEnd(16)} │")
            println("│ Output tokens       │ ${spatialTokens.second.toString().padEnd(16)} │ ${htmlTokens.second.toString().padEnd(16)} │")
            println("└─────────────────────┴──────────────────┴──────────────────┘")

            println("\n" + "=".repeat(80))
        }
    }

    private fun cleanHtmlForComparison(rawHtml: String): String {
        val doc = Jsoup.parse(rawHtml)

        // Remove noise elements
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, " +
                    "head, title, nav, header, footer, aside, img, video, audio"
        ).remove()

        // Strip attributes except essential ones
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("data-ds-id", "class", "role")
            val toRemove = element.attributes().filterNot { it.key in essentialAttrs }
            toRemove.forEach { element.removeAttr(it.key) }
        }

        // Truncate text
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.length > 20) {
                    textNode.text(text.take(20) + "...")
                }
            }
        }

        return doc.body()?.html() ?: ""
    }

    private val rawHtmlSystemInstruction = """
        Identify table structures in this HTML and return their root container IDs.
        
        Instructions:
        - Find elements with data-ds-id that are table/grid roots
        - Tables may be <table> elements OR div-based layouts
        - Return the OUTERMOST container, not individual rows
        - If multiple rows look similar, their parent is likely the table
        
        Return format:
        {
            "tables": [
                {"id": "ds-element-123", "confidence": "high/medium/low", "reason": "..."}
            ]
        }
    """.trimIndent()

    private suspend fun identifyTablesWithRawHtml(
        cleanedHtml: String
    ): Triple<List<LlmTableResult>, Long, Triple<Int, Int, Int>> {
        var promptTokens = 0
        var outputTokens = 0
        var totalTokens = 0

        val config = GenerateContentConfig.builder()
            .temperature(0f)
            .responseSchema(outputSchema)
            .responseMimeType("application/json")
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
            .systemInstruction(Content.fromParts(Part.fromText(rawHtmlSystemInstruction)))
            .build()

        // Truncate HTML if too long
        val truncatedHtml = if (cleanedHtml.length > 100000) {
            cleanedHtml.take(100000) + "\n... [truncated]"
        } else {
            cleanedHtml
        }

        val contents = listOf(
            Content.builder()
                .role("user")
                .parts(listOf(Part.fromText(truncatedHtml)))
                .build()
        )

        val startTime = System.currentTimeMillis()
        val response = client.models.generateContent(
            "gemini-2.5-flash-lite-preview-09-2025",
            contents,
            config
        )
        val latencyMs = System.currentTimeMillis() - startTime

        response.usageMetadata().ifPresent { metadata ->
            promptTokens = metadata.promptTokenCount().orElse(0)
            outputTokens = metadata.candidatesTokenCount().orElse(0)
            totalTokens = metadata.totalTokenCount().orElse(0)
        }

        val responseText = response.text() ?: "{\"tables\":[]}"

        val parsed = try {
            json.decodeFromString<TableIdentificationResponse>(responseText)
        } catch (e: Exception) {
            println("Failed to parse raw HTML response: ${e.message}")
            TableIdentificationResponse(emptyList())
        }

        return Triple(
            parsed.tables,
            latencyMs,
            Triple(promptTokens, outputTokens, totalTokens)
        )
    }
}
