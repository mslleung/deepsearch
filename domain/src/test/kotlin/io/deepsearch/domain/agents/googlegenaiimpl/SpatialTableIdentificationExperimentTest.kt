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
import io.deepsearch.domain.detection.TableGridDetector
import io.deepsearch.domain.detection.BoundingBox as DetectionBoundingBox
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Spatial Table Identification Experiment
 * 
 * New architecture:
 * 1. Browser: Capture bounding boxes for all elements in hidden containers (simple data extraction)
 * 2. Domain (Kotlin): Run robust gap-based clustering algorithm to detect table grids
 * 3. LLM: Verify detected table candidates
 * 
 * Benefits:
 * - Browser does simple DOM work only
 * - Table detection algorithm is in Kotlin (testable, debuggable)
 * - No hardcoded CSS patterns
 */
class SpatialTableIdentificationExperimentTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<kotlinx.coroutines.CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()
    private val client by inject<Client>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val tableGridDetector = TableGridDetector()

    @Serializable
    data class TableVerificationResult(
        val is_table: Boolean,
        val table_type: String?, // "comparison", "pricing", "data", "schedule", etc.
        val description: String,
        val headers: List<String>,
        val row_count: Int,
        val column_count: Int
    )

    // ==================== Tests ====================

    @Test
    fun `detect tables on Sleekflow pricing page`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        runSpatialTableDetection("https://sleekflow.io/pricing")
    }

    @Test
    fun `detect tables on OT&P body-check page`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        runSpatialTableDetection("https://www.otandp.com/body-check")
    }

    private suspend fun runSpatialTableDetection(url: String) {
        browserPool.withPage { page ->
            println("\n" + "=".repeat(80))
            println("SPATIAL TABLE DETECTION: $url")
            println("=".repeat(80))

            // Step 1: Load page
            println("\n>>> Step 1: Loading page...")
            page.navigate(url)
            page.waitForLoad()
            println("Page loaded")

            // Step 2: Inject stable IDs
            println("\n>>> Step 2: Injecting stable IDs...")
            val injectionResult = page.injectStableIds()
            println("Injected ${injectionResult.elements} element IDs, ${injectionResult.icons} icon IDs, ${injectionResult.images} image IDs")

            // Step 3: Capture bounding boxes from hidden containers
            println("\n>>> Step 3: Capturing bounding boxes from hidden containers...")
            val hiddenContainerData = page.captureHiddenContainerBoundingBoxes()
            
            println("\nHidden Container Data:")
            println("  - Hidden containers found: ${hiddenContainerData.hiddenContainerCount}")
            println("  - Total elements captured: ${hiddenContainerData.totalElementsCaptured}")

            // Step 4: Run table grid detection algorithm on each hidden container
            println("\n>>> Step 4: Running table grid detection algorithm...")
            
            data class DetectedTable(
                val containerId: String,
                val gridResult: io.deepsearch.domain.detection.TableGridResult,
                val wasHidden: Boolean
            )
            
            val detectedTables = mutableListOf<DetectedTable>()
            
            for (container in hiddenContainerData.hiddenContainers) {
                println("\n  Analyzing container ${container.containerId} (${container.elements.size} elements)...")
                
                // Convert to detection bounding boxes
                val boxes = container.elements.mapValues { (_, box) ->
                    DetectionBoundingBox(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom
                    )
                }
                
                // Debug: Print sample bounding boxes for containers with many elements
                if (boxes.size >= 50) {
                    println("    DEBUG: Sample bounding boxes (first 10):")
                    boxes.entries.take(10).forEach { (id, box) ->
                        println("      $id: left=${box.left.toInt()}, top=${box.top.toInt()}, w=${(box.right-box.left).toInt()}, h=${(box.bottom-box.top).toInt()}")
                    }
                    // Check if boxes have valid sizes
                    val validBoxes = boxes.filter { (_, b) -> (b.right - b.left) > 0 && (b.bottom - b.top) > 0 }
                    println("    DEBUG: Valid boxes: ${validBoxes.size}/${boxes.size}")
                    if (validBoxes.isNotEmpty()) {
                        val minLeft = validBoxes.values.minOf { it.left }
                        val maxRight = validBoxes.values.maxOf { it.right }
                        val minTop = validBoxes.values.minOf { it.top }
                        val maxBottom = validBoxes.values.maxOf { it.bottom }
                        println("    DEBUG: Bounding region: left=$minLeft, top=$minTop, right=$maxRight, bottom=$maxBottom")
                    }
                }
                
                if (boxes.size < 4) {
                    println("    ⚪ Skipped: too few elements")
                    continue
                }
                
                val gridResult = tableGridDetector.detectTable(boxes)
                
                if (gridResult.isTable && gridResult.confidence >= 0.5) {
                    println("    ✅ Table detected: ${gridResult.rowCount}×${gridResult.colCount} grid (confidence: ${String.format("%.2f", gridResult.confidence)})")
                    println("       Reason: ${gridResult.reason}")
                    detectedTables.add(DetectedTable(
                        containerId = container.containerId,
                        gridResult = gridResult,
                        wasHidden = true
                    ))
                } else {
                    println("    ⚪ No table: ${gridResult.reason}")
                }
            }

            // Also analyze visible elements (get from page snapshot)
            println("\n  Analyzing visible page content...")
            val pageSnapshot = page.capturePageSnapshot()
            val visibleBoxes = pageSnapshot.boundingBoxes.mapValues { (_, box) ->
                DetectionBoundingBox(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom
                )
            }
            
            // For visible content, we need to find container elements and analyze their children
            // For now, let's also run the detector on the full page
            if (visibleBoxes.size >= 4) {
                val fullPageResult = tableGridDetector.detectTable(visibleBoxes)
                if (fullPageResult.isTable && fullPageResult.confidence >= 0.5) {
                    println("    ✅ Page-level table pattern detected")
                }
            }

            println("\n" + "=".repeat(80))
            println("DETECTION RESULTS")
            println("=".repeat(80))
            
            if (detectedTables.isEmpty()) {
                println("\nNo tables detected in hidden containers by spatial analysis.")
                println("(This may be because tables are visible or use different patterns)")
            } else {
                println("\nSpatially detected tables (${detectedTables.size}):")
                detectedTables.forEachIndexed { idx, table ->
                    println("\n  ${idx + 1}. Container: ${table.containerId}")
                    println("     Grid: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols")
                    println("     Confidence: ${String.format("%.2f", table.gridResult.confidence)}")
                    println("     Reason: ${table.gridResult.reason}")
                }
            }

            // Step 5: Get HTML and verify with LLM for detected tables
            if (detectedTables.isNotEmpty()) {
                println("\n>>> Step 5: Verifying detected tables with LLM...")
                
                // Re-inject IDs before fetching HTML
                // (captureHiddenContainerBoundingBoxes may trigger React re-renders that remove our IDs)
                println("  Re-injecting stable IDs (in case React removed them)...")
                page.injectStableIds()
                
                data class VerifiedTable(
                    val containerId: String,
                    val gridResult: io.deepsearch.domain.detection.TableGridResult,
                    val html: String,
                    val verification: TableVerificationResult
                )
                
                val verifiedTables = mutableListOf<VerifiedTable>()
                
                for (table in detectedTables) {
                    val selector = "[data-ds-id=\"${table.containerId}\"]"
                    val html = page.getElementHtmlByCssSelector(selector)
                    
                    if (html.isBlank()) {
                        println("  ❌ Could not get HTML for ${table.containerId}")
                        continue
                    }
                    
                    val truncatedHtml = if (html.length > 15000) {
                        html.take(15000) + "\n<!-- truncated -->"
                    } else {
                        html
                    }
                    
                    println("\n  Verifying ${table.containerId}...")
                    val verification = verifyTableWithLlm(truncatedHtml)
                    
                    if (verification.is_table) {
                        verifiedTables.add(VerifiedTable(
                            containerId = table.containerId,
                            gridResult = table.gridResult,
                            html = html,
                            verification = verification
                        ))
                        println("    ✅ LLM confirmed: ${verification.table_type} table")
                        println("       ${verification.description}")
                    } else {
                        println("    ❌ LLM rejected: ${verification.description}")
                    }
                }

                // Final results
                println("\n" + "=".repeat(80))
                println("FINAL VERIFIED TABLES")
                println("=".repeat(80))
                
                if (verifiedTables.isEmpty()) {
                    println("\nNo tables verified by LLM.")
                } else {
                    verifiedTables.forEachIndexed { idx, table ->
                        println("\n${"─".repeat(70)}")
                        println("TABLE ${idx + 1}: ${table.verification.table_type}")
                        println("${"─".repeat(70)}")
                        println("Container ID: ${table.containerId}")
                        println("Spatial: ${table.gridResult.rowCount}×${table.gridResult.colCount} (confidence: ${String.format("%.2f", table.gridResult.confidence)})")
                        println("LLM: ${table.verification.row_count}×${table.verification.column_count}")
                        println("Description: ${table.verification.description}")
                        println("Headers: ${table.verification.headers.joinToString(", ")}")
                        println("\nHTML (first 500 chars):")
                        println(table.html.take(500))
                    }
                }
            }

            println("\n" + "=".repeat(80))
            println("SUMMARY")
            println("=".repeat(80))
            println("Hidden containers analyzed: ${hiddenContainerData.hiddenContainerCount}")
            println("Total elements in hidden containers: ${hiddenContainerData.totalElementsCaptured}")
            println("Spatially detected tables: ${detectedTables.size}")
        }
    }

    // ==================== LLM Verification ====================

    private val verificationSchema = Schema.builder()
        .type("OBJECT")
        .properties(mapOf(
            "is_table" to Schema.builder()
                .type("BOOLEAN")
                .description("Whether this HTML represents a data table")
                .build(),
            "table_type" to Schema.builder()
                .type("STRING")
                .description("Type of table: comparison, pricing, data, schedule, specification, feature, or null if not a table")
                .build(),
            "description" to Schema.builder()
                .type("STRING")
                .description("Brief description of what the table contains or why it's not a table")
                .build(),
            "headers" to Schema.builder()
                .type("ARRAY")
                .items(Schema.builder().type("STRING").build())
                .description("Column headers if identified")
                .build(),
            "row_count" to Schema.builder()
                .type("INTEGER")
                .description("Estimated number of data rows")
                .build(),
            "column_count" to Schema.builder()
                .type("INTEGER")
                .description("Number of columns")
                .build()
        ))
        .required(listOf("is_table", "description", "headers", "row_count", "column_count"))
        .build()

    private val verificationSystemPrompt = """
        You are analyzing an HTML snippet to determine if it represents a DATA TABLE.
        
        A DATA TABLE has:
        - Multiple rows of similar structure with comparable data
        - Clear columns (either via <table> elements, CSS grid, or repeating div patterns)
        - Data that can be compared across rows
        - Examples: pricing tables, feature comparisons, specifications, schedules, test results
        
        NOT a table:
        - Navigation menus or link lists
        - Simple bulleted lists without columnar structure
        - FAQ sections (Q&A pairs)
        - Card layouts with unstructured content
        - Forms with input fields
        - Single items or hero sections
        
        Analyze the HTML structure and content to determine if it's a table.
        Look at:
        - Class names that suggest table/grid/row/column structure
        - Repeating patterns of similar elements
        - Text content that appears to be data values
        
        Be conservative - only confirm as a table if it clearly has a grid/tabular structure with multiple comparable rows.
    """.trimIndent()

    private suspend fun verifyTableWithLlm(html: String): TableVerificationResult {
        val config = GenerateContentConfig.builder()
            .temperature(0f)
            .responseSchema(verificationSchema)
            .responseMimeType("application/json")
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
            .systemInstruction(Content.fromParts(Part.fromText(verificationSystemPrompt)))
            .build()

        val prompt = """
            Analyze this HTML snippet and determine if it represents a data table.
            
            HTML:
            ```html
            $html
            ```
            
            Determine if this is a data table and provide details.
        """.trimIndent()

        val contents = listOf(
            Content.builder()
                .role("user")
                .parts(listOf(Part.fromText(prompt)))
                .build()
        )

        return try {
            val response = client.models.generateContent(
                "gemini-2.0-flash",
                contents,
                config
            )
            
            val responseText = response.text() ?: return TableVerificationResult(
                is_table = false,
                table_type = null,
                description = "LLM returned no response",
                headers = emptyList(),
                row_count = 0,
                column_count = 0
            )
            
            json.decodeFromString<TableVerificationResult>(responseText)
        } catch (e: Exception) {
            println("    LLM error: ${e.message}")
            TableVerificationResult(
                is_table = false,
                table_type = null,
                description = "LLM verification failed: ${e.message}",
                headers = emptyList(),
                row_count = 0,
                column_count = 0
            )
        }
    }
}
