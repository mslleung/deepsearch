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

            // Step 3: Capture page snapshot FIRST (for HTML)
            // This must run BEFORE hidden container capture because:
            // - Hidden container capture reveals/restores containers which may trigger React re-renders
            // - The capture script re-injects IDs at the end to ensure consistency
            // - But we want the snapshot to have stable IDs before any reveal/restore cycles
            println("\n>>> Step 3: Capturing page snapshot...")
            val pageSnapshot = page.capturePageSnapshot()
            println("Captured page HTML: ${pageSnapshot.html.length} chars")

            // Step 4: Capture bounding boxes from hidden containers
            // This reveals containers, re-injects IDs, captures bboxes, restores, and re-injects again
            println("\n>>> Step 4: Capturing bounding boxes from hidden containers...")
            val hiddenContainerData = page.captureHiddenContainerBoundingBoxes()
            
            println("\nHidden Container Data:")
            println("  - Hidden containers found: ${hiddenContainerData.hiddenContainerCount}")
            println("  - Total elements captured: ${hiddenContainerData.totalElementsCaptured}")
            
            // Debug: Print container info
            for (container in hiddenContainerData.hiddenContainers) {
                println("  - Container [${container.containerLocator.take(50)}...]: ${container.elements.size} elements")
            }
            
            // Verify accordion sections are captured (for Sleekflow pricing page)
            if (url.contains("sleekflow.io/pricing")) {
                val expectedAccordionSections = listOf(
                    "SleekFlow AI",
                    "Omnichannel engagement",
                    "Integrations",
                    "Security",
                    "Support and service"
                )
                
                val allHiddenHtml = hiddenContainerData.hiddenContainers.joinToString("\n") { it.containerHtml }
                
                println("\n" + "=".repeat(40))
                println("ACCORDION VERIFICATION:")
                println("=".repeat(40))
                
                val foundSections = mutableListOf<String>()
                val missingSections = mutableListOf<String>()
                
                for (section in expectedAccordionSections) {
                    val found = allHiddenHtml.contains(section, ignoreCase = true)
                    if (found) {
                        foundSections.add(section)
                        println("  [FOUND] $section")
                    } else {
                        missingSections.add(section)
                        println("  [MISSING] $section")
                    }
                }
                
                // Count <details> containers
                val detailsContainers = hiddenContainerData.hiddenContainers.filter { 
                    it.containerLocator.contains("details") 
                }
                println("\n  Total <details> containers: ${detailsContainers.size}")
                
                println("\n" + "=".repeat(40))
                println("SUMMARY:")
                println("  Found ${foundSections.size}/${expectedAccordionSections.size} accordion sections")
                if (missingSections.isNotEmpty()) {
                    println("  MISSING: $missingSections")
                }
                println("=".repeat(40))
                
                // Assert all 5 accordions are found
                require(foundSections.size >= 5) {
                    "Expected to find all 5 accordion sections but found only ${foundSections.size}. Missing: $missingSections"
                }
            }

            // Step 5: Recursive table discovery (Kotlin-side DOM traversal + spatial analysis)
            // Uses containerHtml with local IDs (data-ds-local), independent of main page snapshot
            println("\n>>> Step 5: Running recursive table discovery (Kotlin-side)...")
            val discoveredTables = recursiveTableDiscoveryService.discoverTablesFromHiddenContainers(
                hiddenContainerData = hiddenContainerData
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
                    println("\n  ${idx + 1}. Element: ${table.localElementId} (depth=${table.depth})")
                    println("     Container locator: ${table.containerLocator.take(60)}...")
                    println("     Grid: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols")
                    println("     Confidence: ${String.format("%.2f", table.gridResult.confidence)}")
                    println("     Leaf elements: ${table.elementBoundingBoxes.size}")
                    println("     Reason: ${table.gridResult.reason}")
                }
            }

            // Step 6: Process detected tables with TableInterpretationAgent
            // Hidden tables use containerHtml with local IDs (data-ds-local)
            if (discoveredTables.isNotEmpty()) {
                println("\n>>> Step 6: Processing detected tables with TableInterpretationAgent...")
                
                data class InterpretedTable(
                    val table: DiscoveredTable,
                    val html: String,
                    val classification: String,
                    val markdown: String
                )
                
                val interpretedTables = mutableListOf<InterpretedTable>()
                
                for (table in discoveredTables) {
                    // Parse the container HTML (contains data-ds-local attributes)
                    val containerDoc = org.jsoup.Jsoup.parse(table.containerHtml)
                    
                    // Find the table element using local ID
                    val selector = "[data-ds-local=\"${table.localElementId}\"]"
                    val tableElement = containerDoc.selectFirst(selector)
                    
                    if (tableElement == null) {
                        println("  ❌ Could not find element for ${table.localElementId}")
                        continue
                    }
                    
                    val html = tableElement.outerHtml()
                    
                    println("\n  Processing ${table.localElementId} (depth=${table.depth})...")
                    println("    Container locator: ${table.containerLocator.take(50)}...")
                    
                    // Create TableIdentification for the input
                    val tableIdentification = TableIdentification(
                        cssSelector = selector,
                        dataId = table.localElementId,
                        auxiliaryInfo = "Recursively discovered table at depth ${table.depth}: ${table.gridResult.rowCount} rows × ${table.gridResult.colCount} cols, confidence: ${String.format("%.2f", table.gridResult.confidence)}. Reason: ${table.gridResult.reason}",
                        containsMedia = tableElement.select("img, svg").isNotEmpty()
                    )
                    
                    // For hidden tables, bounding boxes are local to containerHtml
                    val input = TableInterpretationInput(
                        tableIdentification = tableIdentification,
                        tableHtml = html,
                        boundingBoxes = emptyMap() // Hidden tables don't have bboxes in main snapshot
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
                        println("Local Element ID: ${result.table.localElementId}")
                        println("Container Locator: ${result.table.containerLocator}")
                        println("Depth: ${result.table.depth}")
                        println("Spatial: ${result.table.gridResult.rowCount} rows × ${result.table.gridResult.colCount} cols (confidence: ${String.format("%.2f", result.table.gridResult.confidence)})")
                        println("Classification: ${result.classification}")
                        println("\nMarkdown output:")
                        println(result.markdown)
                        println("\nHTML (first 500 chars):")
                        println(result.html.take(500))
                    }
                }
                
                // Output tables grouped by accordion section (for Sleekflow pricing page)
                if (url.contains("sleekflow.io/pricing")) {
                    println("\n" + "=".repeat(80))
                    println("TABLES BY ACCORDION SECTION")
                    println("=".repeat(80))
                    
                    val expectedAccordionSections = listOf(
                        "SleekFlow AI",
                        "Omnichannel engagement",
                        "Integrations",
                        "Security",
                        "Support and service"
                    )
                    
                    for (accordionName in expectedAccordionSections) {
                        println("\n" + "━".repeat(70))
                        println("ACCORDION: $accordionName")
                        println("━".repeat(70))
                        
                        // Find tables that belong to this accordion
                        // A table belongs to an accordion if its containerHtml contains the accordion name
                        val tablesInAccordion = interpretedTables.filter { result ->
                            result.table.containerHtml.contains(accordionName, ignoreCase = true)
                        }
                        
                        if (tablesInAccordion.isEmpty()) {
                            println("  No tables detected in this accordion.")
                        } else {
                            println("  Tables found: ${tablesInAccordion.size}")
                            
                            tablesInAccordion.forEachIndexed { idx, result ->
                                println("\n  ${"─".repeat(60)}")
                                println("  TABLE ${idx + 1} in '$accordionName'")
                                println("  ${"─".repeat(60)}")
                                println("  Local Element ID: ${result.table.localElementId}")
                                println("  Grid: ${result.table.gridResult.rowCount} rows × ${result.table.gridResult.colCount} cols")
                                println("  Classification: ${result.classification}")
                                println("\n  MARKDOWN OUTPUT:")
                                // Indent the markdown for better readability
                                result.markdown.lines().forEach { line ->
                                    println("    $line")
                                }
                            }
                        }
                    }
                    
                    // Summary of accordion tables
                    println("\n" + "=".repeat(80))
                    println("ACCORDION TABLES SUMMARY")
                    println("=".repeat(80))
                    
                    for (accordionName in expectedAccordionSections) {
                        val tablesInAccordion = interpretedTables.filter { result ->
                            result.table.containerHtml.contains(accordionName, ignoreCase = true)
                        }
                        val tableCount = tablesInAccordion.size
                        val status = if (tableCount > 0) "✅" else "⚠️"
                        println("  $status $accordionName: $tableCount table(s)")
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
