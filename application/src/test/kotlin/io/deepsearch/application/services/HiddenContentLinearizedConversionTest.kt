package io.deepsearch.application.services

import io.deepsearch.application.config.applicationBenchmarkTestModule
import io.deepsearch.domain.agents.ILinearizedContentConversionAgent
import io.deepsearch.domain.agents.LinearizedContentConversionInput
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.IApplicationCoroutineScope
import io.deepsearch.domain.services.DiscoveredTable
import io.deepsearch.domain.services.IRecursiveTableDiscoveryService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject
import kotlin.time.Duration.Companion.seconds

/**
 * Isolated test for the LinearizedContentConversionAgent on hidden container content.
 * Uses spatial analysis to find table candidates, then converts non-semantic (div/CSS)
 * candidates to linearized row format via the new agent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HiddenContentLinearizedConversionTest : KoinTest {

    private val browserPool by inject<IBrowserPool>()
    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val recursiveTableDiscoveryService by inject<IRecursiveTableDiscoveryService>()
    private val linearizedContentConversionAgent by inject<ILinearizedContentConversionAgent>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    @BeforeAll
    fun setup() {
        startKoin { modules(applicationBenchmarkTestModule) }
    }

    @AfterAll
    fun teardown() {
        applicationScope.close()
        stopKoin()
    }

    @Test
    fun `linearized conversion on OT&P body check page`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        runLinearizedConversion("https://www.otandp.com/body-check/")
    }

    @Test
    fun `linearized conversion on SleekFlow pricing page`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        runLinearizedConversion("https://sleekflow.io/pricing")
    }

    private suspend fun runLinearizedConversion(url: String) {
        browserPool.withPage { page ->
            println("\n" + "=".repeat(80))
            println("LINEARIZED CONTENT CONVERSION: $url")
            println("=".repeat(80))

            page.navigate(url)
            page.waitForLoad()
            page.injectStableIds()
            page.capturePageSnapshot()
            val hiddenContainerData = page.captureHiddenContainerBoundingBoxes()

            println("\nHidden containers: ${hiddenContainerData.hiddenContainerCount}, elements: ${hiddenContainerData.totalElementsCaptured}")

            val discoveredTables = recursiveTableDiscoveryService.discoverTablesFromHiddenContainers(
                hiddenContainerData = hiddenContainerData
            )

            println("\nDiscovered table candidates (before dedup): ${discoveredTables.size}")

            // ===== Pre-LLM structural dedup: remove DOM ancestor/descendant overlaps =====
            // Group by containerDataId, then within each group remove nested candidates
            val byContainer = discoveredTables.groupBy { it.containerDataId }
            val dedupedTables = mutableListOf<DiscoveredTable>()
            for ((containerDataId, containerTables) in byContainer) {
                val deduped = deduplicateNestedCandidates(containerTables)
                if (deduped.size < containerTables.size) {
                    println("  Pre-LLM dedup [$containerDataId]: ${containerTables.size} -> ${deduped.size} candidates")
                }
                dedupedTables.addAll(deduped)
            }
            println("Discovered table candidates (after dedup): ${dedupedTables.size}")

            val nonSemanticCandidates = mutableListOf<Pair<DiscoveredTable, String>>()
            for (table in dedupedTables) {
                val containerDoc = Jsoup.parse(table.containerHtml)
                val element = containerDoc.selectFirst("[data-ds-local=\"${table.localElementId}\"]")
                    ?: continue
                val tagName = element.tagName().lowercase()
                when (tagName) {
                    "table", "ul", "ol" -> {
                        println("  Skip $tagName (semantic): ${table.localElementId}")
                        continue
                    }
                    else -> {
                        nonSemanticCandidates.add(table to element.outerHtml())
                    }
                }
            }

            if (nonSemanticCandidates.isEmpty()) {
                println("\nNo non-semantic hidden table candidates to convert. Done.")
                return@withPage
            }

            println("\n>>> Converting ${nonSemanticCandidates.size} non-semantic candidates with LinearizedContentConversionAgent...")
            val inputs = nonSemanticCandidates.map { (table, html) ->
                LinearizedContentConversionInput(
                    html = html,
                    auxiliaryInfo = "Depth=${table.depth}, ${table.gridResult.rowCount}x${table.gridResult.colCount}, confidence=${"%.0f".format(table.gridResult.confidence * 100)}%",
                    containsMedia = false
                )
            }
            val results = linearizedContentConversionAgent.generateBatch(inputs)

            // ===== Post-LLM content dedup =====
            data class AcceptedResult(val index: Int, val output: io.deepsearch.domain.agents.LinearizedContentConversionOutput, val normalizedText: String)
            val accepted = mutableListOf<AcceptedResult>()
            
            results.forEachIndexed { index, output ->
                if (output.classification.shouldRemoveFromDom()) return@forEachIndexed
                val normalizedText = output.structuredText.lowercase().replace(Regex("\\s+"), " ").trim()
                if (normalizedText.isBlank()) return@forEachIndexed
                
                var isSubsumed = false
                val toRemove = mutableListOf<Int>()
                
                for ((i, acc) in accepted.withIndex()) {
                    val wordsA = normalizedText.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                    val wordsB = acc.normalizedText.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                    if (wordsA.isEmpty()) { isSubsumed = true; break }
                    val overlapRatio = wordsA.count { it in wordsB }.toDouble() / wordsA.size
                    val reverseOverlapRatio = if (wordsB.isEmpty()) 1.0 else wordsB.count { it in wordsA }.toDouble() / wordsB.size
                    
                    if (overlapRatio > 0.80) { isSubsumed = true; break }
                    if (reverseOverlapRatio > 0.80) { toRemove.add(i) }
                }
                
                if (!isSubsumed) {
                    toRemove.sortedDescending().forEach { accepted.removeAt(it) }
                    accepted.add(AcceptedResult(index, output, normalizedText))
                }
            }

            println("\n" + "=".repeat(80))
            println("LINEARIZED OUTPUT (${accepted.size} unique results from ${results.size} LLM outputs)")
            println("=".repeat(80))
            accepted.forEachIndexed { i, acc ->
                val table = nonSemanticCandidates[acc.index].first
                println("\n--- Result ${i + 1}: ${acc.output.classification} [${table.localElementId}, depth=${table.depth}, ${table.gridResult.rowCount}x${table.gridResult.colCount}] ---")
                println(acc.output.structuredText.take(2000))
                if (acc.output.structuredText.length > 2000) println("... [truncated]")
            }
            println("\n" + "=".repeat(80))

            assertTrue(
                accepted.isNotEmpty(),
                "Should have at least one conversion result when candidates exist"
            )
        }
    }
    
    /**
     * Remove candidates that are DOM descendants of other candidates in the same container.
     * Mirrors the dedup logic in WebpageExtractionService.deduplicateNestedCandidates().
     */
    private fun deduplicateNestedCandidates(candidates: List<DiscoveredTable>): List<DiscoveredTable> {
        if (candidates.size <= 1) return candidates
        
        val containerDoc = Jsoup.parse(candidates.first().containerHtml)
        val candidateElements = candidates.mapNotNull { candidate ->
            val element = containerDoc.selectFirst("[data-ds-local=\"${candidate.localElementId}\"]")
            if (element != null) candidate to element else null
        }
        if (candidateElements.size <= 1) return candidateElements.map { it.first }
        
        val subsumed = mutableSetOf<String>()
        for ((candidateA, elemA) in candidateElements) {
            for ((candidateB, _) in candidateElements) {
                if (candidateA.localElementId == candidateB.localElementId) continue
                if (candidateB.localElementId in subsumed) continue
                val bIsInsideA = elemA.selectFirst("[data-ds-local=\"${candidateB.localElementId}\"]") != null
                if (bIsInsideA) subsumed.add(candidateB.localElementId)
            }
        }
        return candidates.filter { it.localElementId !in subsumed }
    }
}
