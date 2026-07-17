package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.llm.ILlmClient
import io.deepsearch.domain.agents.infra.llm.LlmResponse
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A/B comparison test across Gemini and Gemma 4 models using [ILlmClient].
 *
 * Now that Gemma 4 routes through the OpenAI-compatible endpoint, structured output
 * (responseSchema) is properly enforced for all models. This test measures:
 *   - Token speed (tokens/sec)
 *   - Cost (USD)
 *   - Structured output compliance (valid JSON matching the schema)
 *   - Quality (printed for manual comparison)
 */
class ModelComparisonTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val llmClient by inject<ILlmClient>()
    private val browserPool by inject<IBrowserPool>()

    private val modelsUnderTest = listOf(
        ModelIds.GEMINI_3_5_FLASH,
        ModelIds.GEMINI_3_1_FLASH_LITE,
        ModelIds.GEMMA_4_26B_A4B,
    )

    private data class ModelResult(
        val modelId: String,
        val latencyMs: Long,
        val promptTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val outputTokensPerSecond: Double,
        val estimatedCostUsd: Double,
        val rawOutput: String,
        val structuredOutputValid: Boolean,
        val error: String? = null,
    )

    // ─── Schema for structured text extraction ──────────────────────────

    private val pricingTierSchema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "tiers" to Schema.builder()
                    .type("ARRAY")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "tierName" to Schema.builder().type("STRING")
                                        .description("Name of the pricing tier").build(),
                                    "monthlyPrice" to Schema.builder().type("STRING")
                                        .description("Monthly price or 'Custom'").build(),
                                    "annualPrice" to Schema.builder().type("STRING")
                                        .description("Annual per-month price, or empty if not applicable").build(),
                                    "features" to Schema.builder().type("ARRAY")
                                        .items(Schema.builder().type("STRING").build())
                                        .description("Key features of this tier").build(),
                                )
                            )
                            .required(listOf("tierName", "monthlyPrice", "features"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("tiers"))
        .build()

    // ─── Schema for structured image segmentation ───────────────────────

    private val segmentationSchema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "elements" to Schema.builder()
                    .type("ARRAY")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                mapOf(
                                    "label" to Schema.builder().type("STRING")
                                        .description("One of: header, footer, table, popup, cookieBanner")
                                        .enum_(listOf("header", "footer", "table", "popup", "cookieBanner"))
                                        .build(),
                                    "box_2d" to Schema.builder().type("ARRAY")
                                        .items(Schema.builder().type("INTEGER").build())
                                        .description("Bounding box [ymin, xmin, ymax, xmax] scaled 0-1000")
                                        .build(),
                                    "description" to Schema.builder().type("STRING")
                                        .description("Brief description of the element").build(),
                                )
                            )
                            .required(listOf("label", "box_2d"))
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("elements"))
        .build()

    // ─── Test 1: Structured text extraction ─────────────────────────────

    @Test
    fun `compare structured text extraction across models`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        val prompt = """
            Extract the pricing tiers from the following webpage text.
            For each tier return: name, monthly price, annual price (if mentioned), and a list of key features.
            
            WEBPAGE TEXT:
            $SAMPLE_PRICING_TEXT
        """.trimIndent()

        val config = GenerateContentConfig.builder()
            .temperature(0F)
            .responseSchema(pricingTierSchema)
            .responseMimeType("application/json")
            .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
            .build()

        val results = mutableListOf<ModelResult>()

        for (model in modelsUnderTest) {
            val result = runModel(model, prompt, config)
            results.add(result)
        }

        printComparisonTable("Structured Text Extraction", results)
        printQualityOutputs(results)
    }

    // ─── Test 2: Structured image segmentation ──────────────────────────

    @Test
    fun `compare structured image segmentation across models`() = runBlocking {
        val url = "https://sleekflow.io/pricing"

        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()

            val screenshot = page.takeFullPageScreenshot()
            println("Screenshot captured: ${screenshot.bytes.size} bytes")

            val prompt = """
                Detect semantic elements and tables in this webpage screenshot.
                For each detected element, provide a label, bounding box, and brief description.
            """.trimIndent()

            val config = GenerateContentConfig.builder()
                .temperature(0F)
                .responseSchema(segmentationSchema)
                .responseMimeType("application/json")
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
                .build()

            val results = mutableListOf<ModelResult>()

            for (model in modelsUnderTest) {
                val result = runModel(model, prompt, config, screenshot.bytes, screenshot.mimeType.value)
                results.add(result)
            }

            printComparisonTable("Structured Image Segmentation ($url)", results)
            printQualityOutputs(results)
        }
    }

    // ─── Core runner ────────────────────────────────────────────────────

    private suspend fun runModel(
        model: ModelIds,
        prompt: String,
        config: GenerateContentConfig,
        imageBytes: ByteArray? = null,
        imageMimeType: String? = null,
    ): ModelResult {
        println("Running ${model.modelId} (backend: ${model.backend})...")
        return try {
            val parts = mutableListOf<Part>()
            if (imageBytes != null && imageMimeType != null) {
                parts.add(Part.fromBytes(imageBytes, imageMimeType))
            }
            parts.add(Part.fromText(prompt))

            val contents = listOf(Content.fromParts(*parts.toTypedArray()))

            val startMs = System.currentTimeMillis()
            val response = llmClient.generateContent(model.modelId, contents, config)
            val latencyMs = System.currentTimeMillis() - startMs

            buildModelResult(model, response, latencyMs)
        } catch (e: Exception) {
            println("  ERROR: ${e.message}")
            errorResult(model, e)
        }
    }

    private fun buildModelResult(
        model: ModelIds,
        response: LlmResponse,
        latencyMs: Long,
    ): ModelResult {
        val promptTokens = response.inputTokens.toInt()
        val outputTokens = response.outputTokens.toInt()
        val totalTokens = response.totalTokens.toInt()

        val tokPerSec = if (latencyMs > 0) outputTokens * 1000.0 / latencyMs else 0.0
        val cost = promptTokens * model.inputPricePerMillion / 1_000_000.0 +
            outputTokens * model.outputPricePerMillion / 1_000_000.0

        val rawOutput = response.text ?: "(no text returned)"

        val structuredValid = try {
            Json.parseToJsonElement(rawOutput)
            true
        } catch (_: Exception) {
            false
        }

        return ModelResult(
            modelId = model.modelId,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            outputTokensPerSecond = tokPerSec,
            estimatedCostUsd = cost,
            rawOutput = rawOutput,
            structuredOutputValid = structuredValid,
        )
    }

    private fun errorResult(model: ModelIds, e: Exception): ModelResult {
        return ModelResult(
            modelId = model.modelId,
            latencyMs = -1,
            promptTokens = 0,
            outputTokens = 0,
            totalTokens = 0,
            outputTokensPerSecond = 0.0,
            estimatedCostUsd = 0.0,
            rawOutput = "",
            structuredOutputValid = false,
            error = e.message ?: e.javaClass.simpleName,
        )
    }

    // ─── Reporting ──────────────────────────────────────────────────────

    private fun printComparisonTable(testName: String, results: List<ModelResult>) {
        val sep = "=".repeat(115)
        println("\n$sep")
        println("MODEL COMPARISON: $testName")
        println(sep)
        println(
            "%-28s | %9s | %9s | %7s | %7s | %10s | %6s | %s".format(
                "Model", "Latency", "Out tok/s", "Prompt", "Output", "Cost (\$)", "JSON?", "Status"
            )
        )
        println("-".repeat(115))

        for (r in results) {
            if (r.error != null) {
                println(
                    "%-28s | %9s | %9s | %7s | %7s | %10s | %6s | %s".format(
                        r.modelId, "-", "-", "-", "-", "-", "-", "ERROR: ${r.error.take(30)}"
                    )
                )
            } else {
                println(
                    "%-28s | %7dms | %9.1f | %7d | %7d | %10.6f | %6s | %s".format(
                        r.modelId,
                        r.latencyMs,
                        r.outputTokensPerSecond,
                        r.promptTokens,
                        r.outputTokens,
                        r.estimatedCostUsd,
                        if (r.structuredOutputValid) "YES" else "NO",
                        "OK"
                    )
                )
            }
        }
        println(sep)
    }

    private fun printQualityOutputs(results: List<ModelResult>) {
        for (r in results) {
            println("\n=== OUTPUT (${r.modelId}) [JSON valid: ${r.structuredOutputValid}] ===")
            if (r.error != null) {
                println("ERROR: ${r.error}")
            } else {
                println(r.rawOutput.take(2000))
                if (r.rawOutput.length > 2000) println("... (truncated)")
            }
        }
        println()
    }

    companion object {
        private val SAMPLE_PRICING_TEXT = """
            Pricing Plans
            
            Free Plan - ${'$'}0/month
            - 100 conversations per month
            - 1 user seat
            - Basic analytics dashboard
            - Email support (48h response)
            - 1 connected channel (WhatsApp or Facebook)
            
            Pro Plan - ${'$'}79/month (billed monthly) or ${'$'}63/month (billed annually)
            - Unlimited conversations
            - 3 user seats (additional seats ${'$'}25/month each)
            - Advanced analytics with export
            - Priority email support (24h response)
            - 5 connected channels
            - Chatbot builder with 5 flows
            - Contact management up to 10,000 contacts
            - Broadcast messaging (1,000/month)
            
            Premium Plan - ${'$'}299/month (billed monthly) or ${'$'}239/month (billed annually)
            - Everything in Pro
            - 5 user seats (additional seats ${'$'}20/month each)
            - Custom analytics dashboards
            - Dedicated account manager
            - Unlimited connected channels
            - Advanced chatbot with unlimited flows
            - Contact management up to 100,000 contacts
            - Broadcast messaging (5,000/month)
            - API access
            - Custom integrations
            
            Enterprise Plan - Custom pricing
            - Everything in Premium
            - Unlimited user seats
            - 24/7 phone support
            - SLA guarantees (99.9% uptime)
            - SSO/SAML authentication
            - Custom data retention policies
            - Dedicated infrastructure
            - Onboarding and training sessions
            - Unlimited contacts and broadcasts
        """.trimIndent()
    }
}
