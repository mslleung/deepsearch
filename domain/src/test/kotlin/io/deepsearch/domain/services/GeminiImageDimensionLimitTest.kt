package io.deepsearch.domain.services

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.config.IApplicationCoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.time.measureTimedValue

/**
 * Simulation test to verify Gemini's actual image dimension limits.
 * 
 * This test empirically determines at what image dimensions Gemini starts
 * returning "Unable to process input image" errors, to validate whether
 * the 4096px limit in ImageDimensionService is actually necessary.
 * 
 * Test dimensions:
 * - Width: 1920px (fixed, typical screenshot width)
 * - Height: Varies from 2000px to 20000px in steps
 * 
 * Prerequisites:
 * - GOOGLE_API_KEY environment variable must be set
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GeminiImageDimensionLimitTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val client by inject<Client>()
    private val applicationScope by inject<IApplicationCoroutineScope>()

    // Store results for final summary
    private val results = mutableListOf<DimensionTestResult>()

    data class DimensionTestResult(
        val width: Int,
        val height: Int,
        val fileSizeKb: Int,
        val success: Boolean,
        val latencyMs: Long,
        val error: String? = null,
        val tokensUsed: Int = 0
    )

    @AfterAll
    fun cleanup() {
        try {
            applicationScope.close()
        } catch (e: Exception) {
            // Koin may not be fully initialized if all tests failed early
        }
        printFinalSummary()
    }

    // ==================== Dimension Tests ====================

    @Test
    @Order(1)
    fun `01 - test 1920x2000 image (baseline - should succeed)`() = runBlocking {
        testImageDimension(1920, 2000)
    }

    @Test
    @Order(2)
    fun `02 - test 1920x3000 image`() = runBlocking {
        testImageDimension(1920, 3000)
    }

    @Test
    @Order(3)
    fun `03 - test 1920x4000 image (just below 4096 limit)`() = runBlocking {
        testImageDimension(1920, 4000)
    }

    @Test
    @Order(4)
    fun `04 - test 1920x4096 image (at the limit)`() = runBlocking {
        testImageDimension(1920, 4096)
    }

    @Test
    @Order(5)
    fun `05 - test 1920x4500 image (above 4096 limit)`() = runBlocking {
        testImageDimension(1920, 4500)
    }

    @Test
    @Order(6)
    fun `06 - test 1920x5000 image`() = runBlocking {
        testImageDimension(1920, 5000)
    }

    @Test
    @Order(7)
    fun `07 - test 1920x6000 image`() = runBlocking {
        testImageDimension(1920, 6000)
    }

    @Test
    @Order(8)
    fun `08 - test 1920x8000 image`() = runBlocking {
        testImageDimension(1920, 8000)
    }

    @Test
    @Order(9)
    fun `09 - test 1920x10000 image`() = runBlocking {
        testImageDimension(1920, 10000)
    }

    @Test
    @Order(10)
    fun `10 - test 1920x15000 image (very tall)`() = runBlocking {
        testImageDimension(1920, 15000)
    }

    @Test
    @Order(11)
    fun `11 - test 1920x20000 image (Wikipedia-like)`() = runBlocking {
        testImageDimension(1920, 20000)
    }

    // ==================== Wide Image Tests ====================

    @Test
    @Order(12)
    fun `12 - test 4096x1080 image (wide)`() = runBlocking {
        testImageDimension(4096, 1080)
    }

    @Test
    @Order(13)
    fun `13 - test 5000x1080 image (very wide)`() = runBlocking {
        testImageDimension(5000, 1080)
    }

    @Test
    @Order(14)
    fun `14 - test 8000x1080 image (extremely wide)`() = runBlocking {
        testImageDimension(8000, 1080)
    }

    // ==================== Square Image Tests ====================

    @Test
    @Order(15)
    fun `15 - test 4096x4096 image (large square)`() = runBlocking {
        testImageDimension(4096, 4096)
    }

    @Test
    @Order(16)
    fun `16 - test 5000x5000 image (very large square)`() = runBlocking {
        testImageDimension(5000, 5000)
    }

    // ==================== Test Implementation ====================

    private suspend fun testImageDimension(width: Int, height: Int) {
        println("\n" + "=".repeat(70))
        println("Testing image: ${width}x${height}")
        println("=".repeat(70))

        // Create test image with visible text showing dimensions
        val imageBytes = createTestImage(width, height)
        val fileSizeKb = imageBytes.size / 1024

        println("Image created: ${fileSizeKb}KB")

        try {
            val (response, duration) = measureTimedValue {
                callGeminiWithImage(imageBytes)
            }

            val tokensUsed = response.second
            println("✅ SUCCESS in ${duration.inWholeMilliseconds}ms")
            println("   Response: ${response.first.take(100)}...")
            println("   Tokens used: $tokensUsed")

            results.add(
                DimensionTestResult(
                    width = width,
                    height = height,
                    fileSizeKb = fileSizeKb,
                    success = true,
                    latencyMs = duration.inWholeMilliseconds,
                    tokensUsed = tokensUsed
                )
            )
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            println("❌ FAILED: $errorMsg")

            results.add(
                DimensionTestResult(
                    width = width,
                    height = height,
                    fileSizeKb = fileSizeKb,
                    success = false,
                    latencyMs = 0,
                    error = errorMsg.take(200)
                )
            )
        }
    }

    /**
     * Calls Gemini API with the image and a simple vision prompt.
     * Returns the response text and token count.
     */
    private fun callGeminiWithImage(imageBytes: ByteArray): Pair<String, Int> {
        val modelId = "gemini-2.5-flash-lite-preview-09-2025"

        val result = client.models.generateContent(
            modelId,
            listOf(
                Content.fromParts(
                    Part.fromBytes(imageBytes, "image/png"),
                    Part.fromText("Describe what you see in this image. What are the dimensions shown?")
                )
            ),
            GenerateContentConfig.builder()
                .temperature(0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )

        val text = result.text() ?: throw RuntimeException("No text response from model")
        val tokens = result.usageMetadata()
            .map { it.totalTokenCount().orElse(0) }
            .orElse(0)

        return Pair(text, tokens)
    }

    /**
     * Creates a PNG test image with the specified dimensions.
     * The image contains a grid pattern and text showing the dimensions,
     * making it easy to verify Gemini is actually processing the full image.
     */
    private fun createTestImage(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()

        // Enable anti-aliasing for better text rendering
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // White background
        g2d.color = Color.WHITE
        g2d.fillRect(0, 0, width, height)

        // Draw grid pattern
        g2d.color = Color(200, 200, 200)
        val gridSize = 100
        for (x in 0 until width step gridSize) {
            g2d.drawLine(x, 0, x, height)
        }
        for (y in 0 until height step gridSize) {
            g2d.drawLine(0, y, width, y)
        }

        // Draw diagonal lines to show full coverage
        g2d.color = Color(150, 150, 255)
        g2d.drawLine(0, 0, width, height)
        g2d.drawLine(width, 0, 0, height)

        // Draw border
        g2d.color = Color.RED
        g2d.drawRect(0, 0, width - 1, height - 1)
        g2d.drawRect(5, 5, width - 11, height - 11)

        // Draw dimension text at various positions
        g2d.color = Color.BLACK
        g2d.font = Font("Arial", Font.BOLD, 48)

        val dimensionText = "Image: ${width}x${height}"

        // Top center
        val fm = g2d.fontMetrics
        val textWidth = fm.stringWidth(dimensionText)
        g2d.drawString(dimensionText, (width - textWidth) / 2, 100)

        // Bottom center
        g2d.drawString(dimensionText, (width - textWidth) / 2, height - 50)

        // Middle
        g2d.font = Font("Arial", Font.BOLD, 72)
        g2d.color = Color.BLUE
        val bigText = "${width} x ${height}"
        val bigTextWidth = g2d.fontMetrics.stringWidth(bigText)
        g2d.drawString(bigText, (width - bigTextWidth) / 2, height / 2)

        // Add position markers every 1000 pixels
        g2d.font = Font("Arial", Font.PLAIN, 24)
        g2d.color = Color(100, 100, 100)
        for (y in 1000 until height step 1000) {
            g2d.drawString("Y=$y", 20, y)
            g2d.drawLine(0, y, width, y)
        }

        g2d.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }

    private fun printFinalSummary() {
        println("\n")
        println("=" .repeat(80))
        println("FINAL SUMMARY: GEMINI IMAGE DIMENSION LIMITS")
        println("=".repeat(80))

        val successful = results.filter { it.success }
        val failed = results.filter { !it.success }

        println("\n📊 Test Results:")
        println("-".repeat(80))
        println(String.format("%-20s %-12s %-10s %-12s %-8s", "Dimensions", "Size (KB)", "Result", "Latency", "Tokens"))
        println("-".repeat(80))

        for (result in results.sortedWith(compareBy({ it.width }, { it.height }))) {
            val status = if (result.success) "✅ OK" else "❌ FAIL"
            val latency = if (result.success) "${result.latencyMs}ms" else "-"
            val tokens = if (result.success) result.tokensUsed.toString() else "-"
            println(
                String.format(
                    "%-20s %-12d %-10s %-12s %-8s",
                    "${result.width}x${result.height}",
                    result.fileSizeKb,
                    status,
                    latency,
                    tokens
                )
            )
        }

        println("-".repeat(80))
        println("\n📈 Summary Statistics:")
        println("   Total tests: ${results.size}")
        println("   Successful: ${successful.size}")
        println("   Failed: ${failed.size}")

        if (successful.isNotEmpty()) {
            val maxSuccessfulHeight = successful.filter { it.width == 1920 }.maxOfOrNull { it.height }
            val maxSuccessfulWidth = successful.filter { it.height == 1080 }.maxOfOrNull { it.width }
            val avgLatency = successful.map { it.latencyMs }.average()

            println("\n   Max successful height (width=1920): ${maxSuccessfulHeight ?: "N/A"}px")
            println("   Max successful width (height=1080): ${maxSuccessfulWidth ?: "N/A"}px")
            println("   Average latency: ${avgLatency.toLong()}ms")
        }

        if (failed.isNotEmpty()) {
            println("\n❌ Failed Dimensions:")
            for (result in failed) {
                println("   ${result.width}x${result.height}: ${result.error?.take(100)}")
            }
        }

        // Determine if 4096 limit is necessary
        println("\n" + "=".repeat(80))
        println("🔍 CONCLUSION: Is the 4096px limit necessary?")
        println("=".repeat(80))

        val above4096Successful = successful.filter { it.width > 4096 || it.height > 4096 }
        val above4096Failed = failed.filter { it.width > 4096 || it.height > 4096 }

        if (above4096Failed.isEmpty() && above4096Successful.isNotEmpty()) {
            println("""
                |
                |   ⚠️  The 4096px limit may be OVERLY CONSERVATIVE!
                |   
                |   Images above 4096px succeeded:
                |${above4096Successful.joinToString("\n") { "     - ${it.width}x${it.height} (${it.fileSizeKb}KB)" }}
                |   
                |   Consider raising or removing the limit.
            """.trimMargin())
        } else if (above4096Failed.isNotEmpty()) {
            val firstFailure = (failed.filter { it.height > 4096 || it.width > 4096 }
                .minByOrNull { maxOf(it.width, it.height) })

            println("""
                |
                |   ✅ The 4096px limit appears JUSTIFIED!
                |   
                |   First failure above 4096px: ${firstFailure?.let { "${it.width}x${it.height}" } ?: "N/A"}
                |   Error: ${firstFailure?.error?.take(100) ?: "N/A"}
                |   
                |   The current limit provides a safety margin.
            """.trimMargin())
        } else {
            println("""
                |
                |   ℹ️  Unable to determine - no images above 4096px were tested
            """.trimMargin())
        }
    }
}
