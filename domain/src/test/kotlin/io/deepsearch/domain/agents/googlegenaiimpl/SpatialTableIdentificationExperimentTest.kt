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
            for ((idx, container) in hiddenContainerData.hiddenContainers.withIndex()) {
                println("  - Container [${container.containerLocator.take(50)}...]: ${container.elements.size} elements")
                
                // Debug: For containers 9-13 (the 5 accordion sections), print bounding box stats
                if (idx in 8..12) {
                    val nonZeroBoxes = container.elements.count { (_, box) -> 
                        (box.right - box.left) > 0 && (box.bottom - box.top) > 0 
                    }
                    val zeroSizeBoxes = container.elements.count { (_, box) ->
                        (box.right - box.left) == 0.0 || (box.bottom - box.top) == 0.0
                    }
                    println("      [DEBUG Container ${idx + 1}] Non-zero boxes: $nonZeroBoxes, Zero-size boxes: $zeroSizeBoxes")
                    
                    // Print first few element bounding boxes
                    if (container.elements.isNotEmpty()) {
                        println("      [DEBUG] Sample bounding boxes:")
                        container.elements.entries.take(5).forEach { (localId, box) ->
                            val w = box.right - box.left
                            val h = box.bottom - box.top
                            println("        $localId: (${box.left}, ${box.top}) ${w}x${h}")
                        }
                    }
                }
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
                
                // Output tables grouped by section
                // Use container-based assignment: each table belongs to the section defined by its container's header
                // This prevents mis-assignment when LLM-generated markdown mentions other section names
                if (url.contains("sleekflow.io/pricing")) {
                    println("\n" + "=".repeat(80))
                    println("TABLES BY ACCORDION SECTION")
                    println("=".repeat(80))
                    
                    val sectionKeywords = listOf(
                        "SleekFlow AI",
                        "Omnichannel engagement",
                        "Integrations",
                        "Security",
                        "Support and service"
                    )
                    
                    // First, build a map of containerLocator -> section name
                    // by looking at which section name appears in the container's header/summary
                    val containerToSection = mutableMapOf<String, String>()
                    
                    for (result in interpretedTables) {
                        val containerLocator = result.table.containerLocator
                        if (containerLocator !in containerToSection) {
                            // Parse container HTML and find section name in summary/header
                            val containerDoc = org.jsoup.Jsoup.parse(result.table.containerHtml)
                            val summaryElements = containerDoc.select("summary, h1, h2, h3, h4, h5, h6, [class*=title], [class*=header]")
                            val summaryText = summaryElements.joinToString(" ") { it.text() }
                            
                            // Find which section this container belongs to
                            for (sectionName in sectionKeywords) {
                                if (summaryText.contains(sectionName, ignoreCase = true)) {
                                    containerToSection[containerLocator] = sectionName
                                    break
                                }
                            }
                        }
                    }
                    
                    // Debug: print container to section mapping
                    println("\nContainer to Section Mapping:")
                    containerToSection.entries.forEach { (locator, section) ->
                        println("  ${locator.take(50)}... -> $section")
                    }
                    
                    // Assign each table to its container's section
                    val sectionToTables = sectionKeywords.associateWith { mutableListOf<InterpretedTable>() }
                    val unassignedTables = mutableListOf<InterpretedTable>()
                    
                    for (result in interpretedTables) {
                        val section = containerToSection[result.table.containerLocator]
                        if (section != null) {
                            sectionToTables[section]!!.add(result)
                        } else {
                            unassignedTables.add(result)
                        }
                    }
                    
                    // Print results by section
                    for (sectionName in sectionKeywords) {
                        println("\n" + "━".repeat(70))
                        println("SECTION: $sectionName")
                        println("━".repeat(70))
                        
                        val tablesInSection = sectionToTables[sectionName]!!
                        
                        if (tablesInSection.isEmpty()) {
                            println("  No tables found in '$sectionName' section.")
                        } else {
                            println("  Tables found: ${tablesInSection.size}")
                            
                            tablesInSection.forEachIndexed { idx, result ->
                                println("\n  ${"─".repeat(60)}")
                                println("  TABLE ${idx + 1} in '$sectionName' section")
                                println("  ${"─".repeat(60)}")
                                println("  Local Element ID: ${result.table.localElementId}")
                                println("  Container: ${result.table.containerLocator.take(60)}...")
                                println("  Depth: ${result.table.depth}")
                                println("  Grid: ${result.table.gridResult.rowCount} rows × ${result.table.gridResult.colCount} cols")
                                println("  Confidence: ${String.format("%.2f", result.table.gridResult.confidence)}")
                                println("  Classification: ${result.classification}")
                                println("\n  MARKDOWN OUTPUT:")
                                result.markdown.lines().forEach { line ->
                                    println("    $line")
                                }
                            }
                        }
                    }
                    
                    // Print unassigned tables if any
                    if (unassignedTables.isNotEmpty()) {
                        println("\n" + "━".repeat(70))
                        println("UNASSIGNED TABLES (${unassignedTables.size})")
                        println("━".repeat(70))
                        unassignedTables.forEachIndexed { idx, result ->
                            println("  ${idx + 1}. ${result.table.localElementId}: ${result.classification}")
                        }
                    }
                    
                    // Summary
                    println("\n" + "=".repeat(80))
                    println("SECTION TABLES SUMMARY")
                    println("=".repeat(80))
                    
                    var totalAssigned = 0
                    var allSectionsFound = true
                    for (sectionName in sectionKeywords) {
                        val tableCount = sectionToTables[sectionName]!!.size
                        totalAssigned += tableCount
                        val status = if (tableCount > 0) "✅" else "⚠️"
                        println("  $status $sectionName: $tableCount table(s)")
                        if (tableCount == 0) allSectionsFound = false
                    }
                    
                    println("\n  Total assigned: $totalAssigned")
                    println("  Unassigned: ${unassignedTables.size}")
                    println("  Total interpreted: ${interpretedTables.size}")
                    
                    // Verify no duplicates: total assigned + unassigned should equal total interpreted
                    require(totalAssigned + unassignedTables.size == interpretedTables.size) {
                        "Duplicate detection error: assigned=$totalAssigned + unassigned=${unassignedTables.size} != total=${interpretedTables.size}"
                    }
                    
                    // Verify all 5 sections have at least one table
                    println("\n" + "─".repeat(40))
                    if (allSectionsFound) {
                        println("  ✅ All 5 accordion sections have detected tables!")
                    } else {
                        val missingSections = sectionKeywords.filter { sectionName ->
                            sectionToTables[sectionName]!!.isEmpty()
                        }
                        println("  ⚠️ Some sections missing tables: $missingSections")
                    }
                    
                    // Assert that all sections have tables
                    require(allSectionsFound) {
                        val missingSections = sectionKeywords.filter { sectionName ->
                            sectionToTables[sectionName]!!.isEmpty()
                        }
                        "Expected all 5 accordion sections to have detected tables, but missing: $missingSections"
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
