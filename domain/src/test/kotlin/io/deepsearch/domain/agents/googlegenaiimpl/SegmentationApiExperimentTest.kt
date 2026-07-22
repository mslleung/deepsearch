package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.services.IImageDimensionService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import java.util.Base64
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Experiment test to evaluate Gemini's segmentation API for detecting
 * webpage semantic elements with pixel-precise masks.
 * 
 * Following the Gemini docs pattern:
 * - No responseSchema
 * - No responseMimeType  
 * - Model returns markdown-fenced JSON list
 * - Parse JSON from ```json ... ``` blocks
 */
class SegmentationApiExperimentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val browserPool by inject<IBrowserPool>()
    private val client by inject<Client>()
    private val imageDimensionService by inject<IImageDimensionService>()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }

    @Serializable
    data class SegmentationItem(
        val box_2d: List<Int>,
        val mask: String? = null,
        val label: String
    )

    /**
     * Test segmentation API following Gemini docs pattern.
     * Returns JSON list with box_2d, mask, and label for each element.
     */
    @Test
    fun `test segmentation for visual identification`() = runTest(testCoroutineDispatcher, timeout = 120.seconds) {
        val url = "https://sleekflow.io/pricing"
        
        browserPool.withPage { page ->
            println("\n" + "=".repeat(80))
            println("SEGMENTATION API TEST: $url")
            println("=".repeat(80))
            
            page.navigate(url)
            page.waitForLoad()
            
            val screenshot = page.takeFullPageScreenshot()
            val pageSnapshot = page.capturePageSnapshot()
            
            val (screenshotWidth, screenshotHeight) = imageDimensionService.getImageDimensions(screenshot.bytes)
            val pageWidth = screenshotWidth.toDouble()
            val pageHeight = screenshotHeight.toDouble()
            
            println("Screenshot: ${screenshot.bytes.size} bytes")
            println("Page dimensions: ${pageWidth.toInt()}x${pageHeight.toInt()}")
            println()

            // Prompt following Gemini docs pattern - asks for JSON list with segmentation masks
            // Labels are structured as "type: description" for easy parsing
            val prompt = """
                Give the segmentation masks for semantic elements and tables in this webpage screenshot.
                The masks should cover the entire element identified edge-to-edge.
                
                Output a JSON list where each entry contains:
                - "box_2d": bounding box as [ymin, xmin, ymax, xmax] scaled to [0, 1000]
                - "mask": segmentation mask as base64-encoded PNG
                - "label": a single label (header, footer, navSidebar, breadcrumb, cookieBanner, popup, table)
                
                ## SEMANTIC ELEMENTS to detect (use these exact TYPE prefixes):
                - header (1 max)- webpage header/navigation bar at the VERY TOP only (not hero sections)
                - footer (1 max)- webpage footer at the bottom
                - navSidebar (1 max)- side navigation column (not main content sidebar)
                - breadcrumb (1 max)- navigation path like "Home > Category > Page"
                - cookieBanner (1 max)- cookie consent popup/banner
                - popup (0 or more)- modal dialogs overlaying content
                
                ## TABLES to detect (use "table:" prefix):
                - table (0 or more)- any table or grid structure containing data
                  e.g. data tables, comparison tables, pricing tables, feature grids

                Make sure the labels are one of header, footer, navSidebar, breadcrumb, cookieBanner, popup, table
            """.trimIndent()

            val config = GenerateContentConfig.builder()
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
                .build()

            val contents = listOf(
                Content.builder()
                    .role("user")
                    .parts(listOf(
                        Part.fromBytes(screenshot.bytes, "image/png"),
                        Part.fromText(prompt),
                    ))
                    .build()
            )

            var promptTokens = 0
            var outputTokens = 0
            var totalTokens = 0
            
            val startTime = System.currentTimeMillis()
            val response = client.models.generateContent(
                ModelIds.GEMINI_3_5_FLASH_LITE.modelId,
                contents,
                config
            )
            val latencyMs = System.currentTimeMillis() - startTime

            response.usageMetadata().ifPresent { metadata ->
                promptTokens = metadata.promptTokenCount().orElse(0)
                outputTokens = metadata.candidatesTokenCount().orElse(0)
                totalTokens = metadata.totalTokenCount().orElse(0)
            }

            val responseText = response.text() ?: ""
            
            println(">>> RESPONSE")
            println("Latency: ${latencyMs}ms")
            println("Tokens: prompt=$promptTokens, output=$outputTokens, total=$totalTokens")
            println()
            println("Raw response:")
            println(responseText)
            println()

            // Parse JSON from markdown fencing (following Gemini docs pattern)
            val jsonText = parseJson(responseText)
            println(">>> PARSED JSON")
            println(jsonText)
            println()

            // Parse as list of segmentation items
            try {
                val items = json.decodeFromString<List<SegmentationItem>>(jsonText)
                
                println(">>> SEGMENTATION RESULTS (${items.size} items)")
                println()
                
                // Group items by type prefix
                val semanticTypes = listOf("header", "footer", "navSidebar", "breadcrumb", "cookieBanner", "popup")
                val groupedItems = mutableMapOf<String, MutableList<SegmentationItem>>()
                
                for (item in items) {
                    val type = parseTypeFromLabel(item.label)
                    groupedItems.getOrPut(type) { mutableListOf() }.add(item)
                }
                
                // Print semantic elements first
                println("=== SEMANTIC ELEMENTS ===")
                for (type in semanticTypes) {
                    val typeItems = groupedItems[type]
                    if (typeItems != null) {
                        for (item in typeItems) {
                            printSegmentationItem(item, pageWidth, pageHeight)
                        }
                    }
                }
                
                // Print tables
                println("\n=== TABLES ===")
                val tableItems = groupedItems["table"]
                if (tableItems != null) {
                    for (item in tableItems) {
                        printSegmentationItem(item, pageWidth, pageHeight)
                    }
                } else {
                    println("No tables detected")
                }
                
                // Print unknown types (elements that didn't match our expected types)
                val knownTypes = semanticTypes + "table"
                val unknownTypes = groupedItems.keys.filter { it !in knownTypes }
                if (unknownTypes.isNotEmpty()) {
                    println("\n=== OTHER (unrecognized types) ===")
                    for (type in unknownTypes) {
                        for (item in groupedItems[type]!!) {
                            printSegmentationItem(item, pageWidth, pageHeight)
                        }
                    }
                }
                
                // Summary
                println("\n=== SUMMARY ===")
                println("Semantic elements found: ${semanticTypes.filter { groupedItems.containsKey(it) }}")
                println("Tables found: ${groupedItems["table"]?.size ?: 0}")
                println("Unrecognized types: $unknownTypes")
                
            } catch (e: Exception) {
                println("Failed to parse JSON list: ${e.message}")
                e.printStackTrace()
            }

            // Compare with actual DOM elements
            println(">>> ACTUAL DOM ELEMENTS FOR REFERENCE")
            val boundingBoxes = pageSnapshot.boundingBoxes
            
            val headerCandidates = boundingBoxes.entries.filter { (dsId, bbox) ->
                val height = bbox.bottom - bbox.top
                dsId.startsWith("ds-element-") && bbox.top < 200 && height > 50 && height < 150
            }.take(5)
            
            println("Header candidates (top < 200px, height 50-150px):")
            for ((dsId, bbox) in headerCandidates) {
                println("  $dsId: top=${bbox.top.toInt()}, height=${(bbox.bottom - bbox.top).toInt()}px")
            }
            
            println("\n" + "=".repeat(80))
        }
    }

    /**
     * Parse type prefix from structured label (e.g., "header: Navigation bar" -> "header").
     */
    private fun parseTypeFromLabel(label: String): String {
        val colonIndex = label.indexOf(':')
        return if (colonIndex > 0) {
            label.substring(0, colonIndex).trim().lowercase()
        } else {
            // Try to match known types in the label
            val knownTypes = listOf("header", "footer", "navSidebar", "breadcrumb", "cookieBanner", "popup", "table")
            knownTypes.find { label.lowercase().contains(it.lowercase()) } ?: "unknown"
        }
    }

    /**
     * Print a segmentation item with its details.
     */
    private fun printSegmentationItem(item: SegmentationItem, pageWidth: Double, pageHeight: Double) {
        val type = parseTypeFromLabel(item.label)
        val description = if (item.label.contains(':')) {
            item.label.substringAfter(':').trim()
        } else {
            item.label
        }
        
        println("\n[$type] $description")
        println("  box_2d: ${item.box_2d}")
        
        val pixels = convertToPixels(item.box_2d, pageWidth, pageHeight)
        println("  pixels: top=${pixels.top.toInt()}, bottom=${pixels.bottom.toInt()}, height=${pixels.height.toInt()}px, width=${pixels.width.toInt()}px")
        
        if (item.mask != null && item.mask.startsWith("data:image/png;base64,")) {
            println("  mask: PROVIDED (${item.mask.length} chars)")
            
            // Process mask following Gemini docs pattern
            val derivedBounds = processMask(item.mask, item.box_2d, pageWidth, pageHeight)
            if (derivedBounds != null) {
                println("  mask-derived bbox: $derivedBounds")
                val derivedPixels = convertToPixels(derivedBounds, pageWidth, pageHeight)
                println("  mask-derived pixels: height=${derivedPixels.height.toInt()}px")
            }
        } else {
            println("  mask: NOT PROVIDED or invalid format")
        }
    }

    /**
     * Parse JSON from markdown fencing (following Gemini docs pattern).
     */
    private fun parseJson(jsonOutput: String): String {
        val lines = jsonOutput.lines()
        for ((i, line) in lines.withIndex()) {
            if (line.trim() == "```json") {
                val remaining = lines.drop(i + 1).joinToString("\n")
                return remaining.split("```")[0].trim()
            }
        }
        // If no markdown fencing, try to find JSON array directly
        val start = jsonOutput.indexOf('[')
        val end = jsonOutput.lastIndexOf(']')
        return if (start >= 0 && end > start) {
            jsonOutput.substring(start, end + 1)
        } else {
            jsonOutput
        }
    }

    /**
     * Convert [0, 1000] scaled coordinates to pixel coordinates.
     */
    data class PixelBounds(
        val top: Double,
        val left: Double,
        val bottom: Double,
        val right: Double,
        val height: Double,
        val width: Double
    )
    
    private fun convertToPixels(box2d: List<Int>, pageWidth: Double, pageHeight: Double): PixelBounds {
        val ymin = box2d.getOrElse(0) { 0 }
        val xmin = box2d.getOrElse(1) { 0 }
        val ymax = box2d.getOrElse(2) { 1000 }
        val xmax = box2d.getOrElse(3) { 1000 }
        
        val top = ymin * pageHeight / 1000
        val left = xmin * pageWidth / 1000
        val bottom = ymax * pageHeight / 1000
        val right = xmax * pageWidth / 1000
        
        return PixelBounds(
            top = top,
            left = left,
            bottom = bottom,
            right = right,
            height = bottom - top,
            width = right - left
        )
    }

    /**
     * Process segmentation mask following Gemini docs pattern.
     * 
     * The mask is a base64 encoded PNG probability map (values 0-255).
     * It needs to be resized to match bounding box dimensions and binarized
     * at threshold 127 (midpoint).
     */
    private fun processMask(maskBase64: String, box2d: List<Int>, pageWidth: Double, pageHeight: Double): List<Int>? {
        return try {
            // Remove data URL prefix
            val base64Data = maskBase64.removePrefix("data:image/png;base64,")
            val maskBytes = Base64.getDecoder().decode(base64Data)
            val maskImage = ImageIO.read(ByteArrayInputStream(maskBytes))
            
            if (maskImage == null) {
                println("    Could not decode mask image")
                return null
            }
            
            println("    Mask image size: ${maskImage.width}x${maskImage.height}")
            
            // Get bounding box in pixel coordinates
            val y0 = (box2d[0] / 1000.0 * pageHeight).toInt()
            val x0 = (box2d[1] / 1000.0 * pageWidth).toInt()
            val y1 = (box2d[2] / 1000.0 * pageHeight).toInt()
            val x1 = (box2d[3] / 1000.0 * pageWidth).toInt()
            
            val boxWidth = x1 - x0
            val boxHeight = y1 - y0
            
            if (boxWidth <= 0 || boxHeight <= 0) {
                println("    Invalid bounding box dimensions")
                return null
            }
            
            // Find actual mask boundaries by scanning for pixels > 127 (threshold)
            var minY = maskImage.height
            var maxY = 0
            var minX = maskImage.width
            var maxX = 0
            var pixelCount = 0
            
            for (y in 0 until maskImage.height) {
                for (x in 0 until maskImage.width) {
                    val pixel = maskImage.getRGB(x, y)
                    // Check grayscale value (mask is probability map 0-255)
                    val gray = pixel and 0xFF
                    
                    if (gray > 127) {  // Threshold at midpoint
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        pixelCount++
                    }
                }
            }
            
            println("    Mask pixels above threshold: $pixelCount, bounds: ($minX,$minY) to ($maxX,$maxY)")
            
            if (pixelCount == 0) {
                return null
            }
            
            // Convert mask pixel coordinates back to [0, 1000] scale
            // The mask is sized to the bounding box, so we scale relative to that
            val derivedYmin = box2d[0] + (minY * (box2d[2] - box2d[0]) / maskImage.height)
            val derivedYmax = box2d[0] + (maxY * (box2d[2] - box2d[0]) / maskImage.height)
            val derivedXmin = box2d[1] + (minX * (box2d[3] - box2d[1]) / maskImage.width)
            val derivedXmax = box2d[1] + (maxX * (box2d[3] - box2d[1]) / maskImage.width)
            
            listOf(derivedYmin, derivedXmin, derivedYmax, derivedXmax)
        } catch (e: Exception) {
            println("    Failed to process mask: ${e.message}")
            null
        }
    }
}
