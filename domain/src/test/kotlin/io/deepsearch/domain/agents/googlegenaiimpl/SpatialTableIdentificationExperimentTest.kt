package io.deepsearch.domain.agents.googlegenaiimpl

import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.services.DiscoveredTable
import io.deepsearch.domain.services.IRecursiveTableDiscoveryService
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
 * Architecture:
 * 1. Browser: Capture bounding boxes for all elements in hidden containers (simple data extraction)
 * 2. Domain (Kotlin): Recursively traverse DOM + run spatial analysis at each level
 * 3. LLM: Interpret detected table candidates
 * 
 * Benefits:
 * - Browser does simple DOM work only
 * - Table detection algorithm is in Kotlin (testable, debuggable)
 * - Recursive discovery finds nested tables (like accordion sections)
 * - Automatic deduplication of overlapping tables
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
    private val recursiveTableDiscoveryService by inject<IRecursiveTableDiscoveryService>()
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
            println("RECURSIVE SPATIAL TABLE DETECTION: $url")
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
            // (This must run BEFORE page snapshot so any re-injected IDs are in the HTML)
            println("\n>>> Step 3: Capturing bounding boxes from hidden containers...")
            val hiddenContainerData = page.captureHiddenContainerBoundingBoxes()
            
            println("\nHidden Container Data:")
            println("  - Hidden containers found: ${hiddenContainerData.hiddenContainerCount}")
            println("  - Total elements captured: ${hiddenContainerData.totalElementsCaptured}")
            
            // Debug: Print container info
            for (container in hiddenContainerData.hiddenContainers) {
                println("  - Container ${container.containerId}: ${container.elements.size} elements")
            }

            // Step 4: Capture page snapshot (for HTML)
            // (After hidden container processing so HTML has same IDs as bounding boxes)
            println("\n>>> Step 4: Capturing page snapshot...")
            val pageSnapshot = page.capturePageSnapshot()
            println("Captured page HTML: ${pageSnapshot.html.length} chars")

            // Step 5: Recursive table discovery (Kotlin-side DOM traversal + spatial analysis)
            println("\n>>> Step 5: Running recursive table discovery (Kotlin-side)...")
            val discoveredTables = recursiveTableDiscoveryService.discoverTablesFromHiddenContainers(
                hiddenContainerData = hiddenContainerData,
                fullPageHtml = pageSnapshot.html
            )

            println("\n" + "=".repeat(80))
            println("RECURSIVE DISCOVERY RESULTS")
            println("=".repeat(80))
            
            if (discoveredTables.isEmpty()) {
                println("\nNo tables discovered by recursive spatial analysis.")
                println("(This may be because tables are visible or use different patterns)")
            } else {
                println("\nDiscovered tables (${discoveredTables.size}):")
                discoveredTables.forEachIndexed { idx, table ->
                    println("\n  ${idx + 1}. Element: ${table.elementId} (depth=${table.depth})")
                    println("     Grid: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols")
                    println("     Confidence: ${String.format("%.2f", table.gridResult.confidence)}")
                    println("     Leaf elements: ${table.elementBoundingBoxes.size}")
                    println("     Reason: ${table.gridResult.reason}")
                }
            }

            // Step 6: Process detected tables with TableInterpretationAgent
            if (discoveredTables.isNotEmpty()) {
                println("\n>>> Step 6: Processing detected tables with TableInterpretationAgent...")
                
                // Re-inject IDs before fetching HTML
                println("  Re-injecting stable IDs (in case React removed them)...")
                page.injectStableIds()
                
                // Get a fresh page snapshot for bounding boxes
                val freshSnapshot = page.capturePageSnapshot()
                
                data class InterpretedTable(
                    val table: DiscoveredTable,
                    val html: String,
                    val classification: String,
                    val markdown: String
                )
                
                val interpretedTables = mutableListOf<InterpretedTable>()
                
                for (table in discoveredTables) {
                    val selector = "[data-ds-id=\"${table.elementId}\"]"
                    val html = page.getElementHtmlByCssSelector(selector)
                    
                    if (html.isBlank()) {
                        println("  ❌ Could not get HTML for ${table.elementId}")
                        continue
                    }
                    
                    println("\n  Processing ${table.elementId} (depth=${table.depth})...")
                    
                    // Create TableIdentification for the input
                    val tableIdentification = TableIdentification(
                        cssSelector = selector,
                        dataId = table.elementId,
                        auxiliaryInfo = "Recursively discovered table at depth ${table.depth}: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols, confidence: ${String.format("%.2f", table.gridResult.confidence)}. Reason: ${table.gridResult.reason}",
                        containsMedia = false
                    )
                    
                    val input = TableInterpretationInput(
                        tableIdentification = tableIdentification,
                        tableHtml = html,
                        boundingBoxes = freshSnapshot.boundingBoxes
                    )
                    
                    try {
                        val output = tableInterpretationAgent.generate(input)
                        
                        interpretedTables.add(InterpretedTable(
                            table = table,
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
                    interpretedTables.forEachIndexed { idx, result ->
                        println("\n${"─".repeat(70)}")
                        println("TABLE ${idx + 1}: ${result.classification}")
                        println("${"─".repeat(70)}")
                        println("Element ID: ${result.table.elementId}")
                        println("Depth: ${result.table.depth}")
                        println("Spatial: ${result.table.gridResult.rowCount} rows × ${result.table.gridResult.colCount} cols (confidence: ${String.format("%.2f", result.table.gridResult.confidence)})")
                        println("Classification: ${result.classification}")
                        println("\nMarkdown output:")
                        println(result.markdown)
                        println("\nHTML (first 500 chars):")
                        println(result.html.take(500))
                    }
                }
            }

            println("\n" + "=".repeat(80))
            println("SUMMARY")
            println("=".repeat(80))
            println("Hidden containers analyzed: ${hiddenContainerData.hiddenContainerCount}")
            println("Total elements in hidden containers: ${hiddenContainerData.totalElementsCaptured}")
            println("Tables discovered by recursive analysis: ${discoveredTables.size}")
        }
    }

}
