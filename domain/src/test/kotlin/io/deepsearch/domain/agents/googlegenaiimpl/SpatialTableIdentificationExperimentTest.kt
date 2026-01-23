package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.services.ITableGridDetectorService
import io.deepsearch.domain.services.TableDetectionBoundingBox
import io.deepsearch.domain.services.TableGridResult
import kotlinx.coroutines.test.runTest
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
    private val tableGridDetectorService by inject<ITableGridDetectorService>()
    private val tableInterpretationAgent by inject<ITableInterpretationAgent>()

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
                val gridResult: TableGridResult,
                val wasHidden: Boolean
            )
            
            val detectedTables = mutableListOf<DetectedTable>()
            
            for (container in hiddenContainerData.hiddenContainers) {
                println("\n  Analyzing container ${container.containerId} (${container.elements.size} elements)...")
                
                // Convert to detection bounding boxes
                val boxes = container.elements.mapValues { (_, box) ->
                    TableDetectionBoundingBox(
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
                
                val gridResult = tableGridDetectorService.detectTable(boxes)
                
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
                TableDetectionBoundingBox(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom
                )
            }
            
            // For visible content, we need to find container elements and analyze their children
            // For now, let's also run the detector on the full page
            if (visibleBoxes.size >= 4) {
                val fullPageResult = tableGridDetectorService.detectTable(visibleBoxes)
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

            // Step 5: Process detected tables with TableInterpretationAgent
            if (detectedTables.isNotEmpty()) {
                println("\n>>> Step 5: Processing detected tables with TableInterpretationAgent...")
                
                // Re-inject IDs before fetching HTML
                // (captureHiddenContainerBoundingBoxes may trigger React re-renders that remove our IDs)
                println("  Re-injecting stable IDs (in case React removed them)...")
                page.injectStableIds()
                
                // Get a fresh page snapshot for bounding boxes
                val freshSnapshot = page.capturePageSnapshot()
                
                data class InterpretedTable(
                    val containerId: String,
                    val gridResult: TableGridResult,
                    val html: String,
                    val classification: String,
                    val markdown: String
                )
                
                val interpretedTables = mutableListOf<InterpretedTable>()
                
                for (table in detectedTables) {
                    val selector = "[data-ds-id=\"${table.containerId}\"]"
                    val html = page.getElementHtmlByCssSelector(selector)
                    
                    if (html.isBlank()) {
                        println("  ❌ Could not get HTML for ${table.containerId}")
                        continue
                    }
                    
                    println("\n  Processing ${table.containerId}...")
                    
                    // Create TableIdentification for the input
                    val tableIdentification = TableIdentification(
                        cssSelector = selector,
                        dataId = table.containerId,
                        auxiliaryInfo = "Spatially detected table: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols, confidence: ${String.format("%.2f", table.gridResult.confidence)}. Reason: ${table.gridResult.reason}",
                        containsMedia = false
                    )
                    
                    // Get bounding boxes for elements within this table
                    // Filter to only include bounding boxes for elements within the table container
                    val tableBoundingBoxes = freshSnapshot.boundingBoxes.filterKeys { dsId ->
                        // Include bounding boxes that are part of this table's subtree
                        // This is a simplified approach - we include all bounding boxes
                        // In production, we'd filter to only descendants of the table element
                        true
                    }
                    
                    val input = TableInterpretationInput(
                        tableIdentification = tableIdentification,
                        tableHtml = html,
                        boundingBoxes = tableBoundingBoxes
                    )
                    
                    try {
                        val output = tableInterpretationAgent.generate(input)
                        
                        interpretedTables.add(InterpretedTable(
                            containerId = table.containerId,
                            gridResult = table.gridResult,
                            html = html,
                            classification = output.classification.name,
                            markdown = output.markdown
                        ))
                        
                        println("    ✅ Classification: ${output.classification}")
                        println("    Token usage: ${output.tokenUsage.totalTokens} tokens")
                    } catch (e: Exception) {
                        println("    ❌ Error processing table: ${e.message}")
                    }
                }

                // Final results
                println("\n" + "=".repeat(80))
                println("TABLE INTERPRETATION RESULTS")
                println("=".repeat(80))
                
                if (interpretedTables.isEmpty()) {
                    println("\nNo tables successfully interpreted.")
                } else {
                    interpretedTables.forEachIndexed { idx, table ->
                        println("\n${"─".repeat(70)}")
                        println("TABLE ${idx + 1}: ${table.classification}")
                        println("${"─".repeat(70)}")
                        println("Container ID: ${table.containerId}")
                        println("Spatial: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols (confidence: ${String.format("%.2f", table.gridResult.confidence)})")
                        println("Classification: ${table.classification}")
                        println("\nMarkdown output:")
                        println(table.markdown)
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

}
