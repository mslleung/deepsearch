package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.browser.IBrowserPool
import io.deepsearch.domain.config.domainBenchmarkTestModule
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Expanded Gemma 4 experiments comparing it against Flash Lite and Flash
 * on tasks that mirror actual production agent workloads.
 *
 * Focus: Can Gemma 4 26B A4B replace Flash Lite for vision+text tasks?
 */
class Gemma4ExperimentTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainBenchmarkTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val client by inject<Client>()
    private val browserPool by inject<IBrowserPool>()

    private val models = listOf(
        ModelIds.GEMINI_3_6_FLASH,
        ModelIds.GEMINI_3_5_FLASH_LITE,
        ModelIds.GEMMA_4_26B_A4B,
    )

    // ─── Experiment 1: Visual element detection with structured output ─

    @Test
    fun `experiment 1 - visual element detection with responseSchema`() = runBlocking {
        val url = "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"

        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val screenshot = page.takeFullPageScreenshot()
            println("Screenshot: ${screenshot.bytes.size} bytes")

            val systemPrompt = """
                You are a webpage analysis agent. Given a screenshot of a webpage,
                identify the semantic layout elements visible in it.
                Return bounding boxes as [ymin, xmin, ymax, xmax] normalized to [0, 1000].
            """.trimIndent()

            val userPrompt = "Analyze this webpage screenshot for semantic elements and tables."

            val schema = Schema.builder()
                .type("OBJECT")
                .properties(mapOf(
                    "elements" to Schema.builder()
                        .type("ARRAY")
                        .items(Schema.builder()
                            .type("OBJECT")
                            .properties(mapOf(
                                "label" to Schema.builder()
                                    .type("STRING")
                                    .enum_(listOf("header", "footer", "navSidebar", "table", "infobox", "tableOfContents"))
                                    .build(),
                                "box_2d" to Schema.builder()
                                    .type("ARRAY")
                                    .items(Schema.builder().type("INTEGER").build())
                                    .build(),
                                "confidence" to Schema.builder()
                                    .type("STRING")
                                    .enum_(listOf("high", "medium", "low"))
                                    .build()
                            ))
                            .required(listOf("label", "box_2d", "confidence"))
                            .build())
                        .build()
                ))
                .required(listOf("elements"))
                .build()

            val results = models.map { model ->
                runVisionTask(
                    model, screenshot.bytes, screenshot.mimeType.value,
                    systemPrompt, userPrompt, schema
                )
            }

            printComparisonTable("Exp 1: Visual Element Detection (Wikipedia)", results)
            printQualityOutputs(results)
        }
    }

    // ─── Experiment 2: Image classification (mirrors ImageClassificationAgent) ─

    @Test
    fun `experiment 2 - image classification with structured output`() = runBlocking {
        val url = "https://en.wikipedia.org/wiki/Kotlin_(programming_language)"

        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val screenshot = page.takeFullPageScreenshot()

            val systemPrompt = """
                You are an image classifier for webpage content.
                Given an image from a webpage, classify what type of content it shows
                and provide a brief description of what's visible.
            """.trimIndent()

            val userPrompt = "Classify this image and provide a comprehensive description."

            val schema = Schema.builder()
                .type("OBJECT")
                .properties(mapOf(
                    "imageType" to Schema.builder()
                        .type("STRING")
                        .enum_(listOf("photo", "chart", "diagram", "table", "screenshot", "icon", "logo", "infographic", "map", "unknown"))
                        .build(),
                    "imageDescription" to Schema.builder()
                        .type("STRING")
                        .build(),
                    "containsText" to Schema.builder()
                        .type("BOOLEAN")
                        .build(),
                    "needsTableInterpretation" to Schema.builder()
                        .type("BOOLEAN")
                        .build()
                ))
                .required(listOf("imageType", "imageDescription", "containsText", "needsTableInterpretation"))
                .build()

            val results = models.map { model ->
                runVisionTask(
                    model, screenshot.bytes, screenshot.mimeType.value,
                    systemPrompt, userPrompt, schema
                )
            }

            printComparisonTable("Exp 2: Image Classification (Wikipedia page)", results)
            printQualityOutputs(results)
        }
    }

    // ─── Experiment 3: Content extraction from a data-heavy page ──────

    @Test
    fun `experiment 3 - content extraction from data-heavy page`() = runBlocking {
        val url = "https://en.wikipedia.org/wiki/List_of_countries_by_GDP_(nominal)"

        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val screenshot = page.takeFullPageScreenshot()
            println("Screenshot: ${screenshot.bytes.size} bytes")

            val userPrompt = """
                Look at this webpage screenshot. Extract:
                1. The page title
                2. How many data tables are visible
                3. The top 5 countries by GDP if visible in the screenshot
                4. Any notable observations about the page layout
                
                Return as JSON with fields: pageTitle, tableCount, topCountries (array of {country, gdp}), layoutObservations
            """.trimIndent()

            val results = models.map { model ->
                runVisionTaskFreeform(model, screenshot.bytes, screenshot.mimeType.value, userPrompt)
            }

            printComparisonTable("Exp 3: Content Extraction (GDP Wikipedia)", results)
            printQualityOutputs(results)
        }
    }

    // ─── Experiment 4: Structured text extraction (no image) ──────────

    @Test
    fun `experiment 4 - structured entity extraction from text`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        val inputText = """
            SleekFlow is a Hong Kong-based omnichannel social commerce platform founded in 2019 by Henson Tsai.
            The company raised a $8M Series A round led by Tiger Global Management in June 2022,
            followed by a $7M extension in November 2022 with participation from Transcend Capital Partners.
            SleekFlow operates in Southeast Asia, the Middle East, and Europe, serving over 5,000 companies
            including Bupa, Lalamove, and Shiseido. The platform integrates WhatsApp, Facebook Messenger,
            Instagram, LINE, WeChat, Telegram, and SMS into a single inbox for sales and support teams.
            In 2024, SleekFlow launched an AI-powered chatbot builder and expanded to 15 countries.
            The company has over 200 employees across offices in Hong Kong, Singapore, Malaysia,
            Indonesia, Brazil, and the United Kingdom.
        """.trimIndent()

        val systemPrompt = """
            Extract structured entities from the provided text.
            Be thorough and accurate. Extract all entities mentioned.
        """.trimIndent()

        val userPrompt = """
            Extract entities from this text:
            $inputText
        """.trimIndent()

        val schema = Schema.builder()
            .type("OBJECT")
            .properties(mapOf(
                "companies" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder()
                        .type("OBJECT")
                        .properties(mapOf(
                            "name" to Schema.builder().type("STRING").build(),
                            "role" to Schema.builder().type("STRING").build()
                        ))
                        .required(listOf("name", "role"))
                        .build())
                    .build(),
                "people" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder()
                        .type("OBJECT")
                        .properties(mapOf(
                            "name" to Schema.builder().type("STRING").build(),
                            "role" to Schema.builder().type("STRING").build()
                        ))
                        .required(listOf("name", "role"))
                        .build())
                    .build(),
                "fundingRounds" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder()
                        .type("OBJECT")
                        .properties(mapOf(
                            "amount" to Schema.builder().type("STRING").build(),
                            "stage" to Schema.builder().type("STRING").build(),
                            "date" to Schema.builder().type("STRING").build(),
                            "investors" to Schema.builder()
                                .type("ARRAY")
                                .items(Schema.builder().type("STRING").build())
                                .build()
                        ))
                        .required(listOf("amount", "stage", "date"))
                        .build())
                    .build(),
                "locations" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .build(),
                "products" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .build()
            ))
            .required(listOf("companies", "people", "fundingRounds", "locations", "products"))
            .build()

        val results = models.map { model ->
            runTextTask(model, systemPrompt, userPrompt, schema)
        }

        printComparisonTable("Exp 4: Entity Extraction (text only)", results)
        printQualityOutputs(results)
    }

    // ─── Experiment 5: Link relevance analysis (text, mirrors real agent) ─

    @Test
    fun `experiment 5 - link relevance analysis`() = runTest(testCoroutineDispatcher, timeout = 300.seconds) {
        val userPrompt = """
            Given the user query and the list of links found on a webpage,
            rate each link's relevance to answering the query.
            
            USER QUERY: "What are the pricing plans for SleekFlow and what features does each tier include?"
            
            PAGE URL: https://sleekflow.io
            
            LINKS FOUND:
            1. /pricing - "Pricing"
            2. /features - "Features"
            3. /blog - "Blog"
            4. /about - "About Us"
            5. /contact - "Contact Sales"
            6. /features/whatsapp-business-api - "WhatsApp Business API"
            7. /features/chatbot - "AI Chatbot Builder"
            8. /case-studies - "Case Studies"
            9. /pricing#enterprise - "Enterprise Plan"
            10. /docs/api - "API Documentation"
            11. /partners - "Partner Program"
            12. /features/automation - "Flow Builder & Automation"
            
            For each link, provide: url, relevanceScore (0-100), reasoning (brief).
        """.trimIndent()

        val results = models.map { model ->
            runTextTaskFreeform(model, userPrompt)
        }

        printComparisonTable("Exp 5: Link Relevance Analysis (text only)", results)
        printQualityOutputs(results)
    }

    // ─── Experiment 6: Screenshot → markdown conversion ───────────────

    @Test
    fun `experiment 6 - screenshot to markdown content extraction`() = runBlocking {
        val url = "https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax"

        browserPool.withPage { page ->
            page.navigate(url)
            page.waitForLoad()
            val screenshot = page.takeFullPageScreenshot()
            println("Screenshot: ${screenshot.bytes.size} bytes")

            val userPrompt = """
                Convert the visible content of this webpage screenshot into clean markdown.
                Preserve the heading hierarchy, code blocks, lists, and links.
                Only convert what you can actually see in the screenshot.
                Be faithful to the original content — do not hallucinate or add information not visible.
            """.trimIndent()

            val results = models.map { model ->
                runVisionTaskFreeform(model, screenshot.bytes, screenshot.mimeType.value, userPrompt)
            }

            printComparisonTable("Exp 6: Screenshot → Markdown (GitHub docs)", results)
            printQualityOutputs(results)
        }
    }

    // ─── Core helpers ─────────────────────────────────────────────────

    private data class ModelResult(
        val modelId: String,
        val latencyMs: Long,
        val promptTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val outputTokensPerSecond: Double,
        val estimatedCostUsd: Double,
        val rawOutput: String,
        val error: String? = null,
    )

    private fun runVisionTask(
        model: ModelIds,
        imageBytes: ByteArray,
        imageMimeType: String,
        systemPrompt: String,
        userPrompt: String,
        responseSchema: Schema,
    ): ModelResult {
        println("  Running ${model.modelId}...")
        return try {
            val contents = listOf(Content.fromParts(
                Part.fromBytes(imageBytes, imageMimeType),
                Part.fromText(userPrompt),
            ))
            val config = GenerateContentConfig.builder()
                .responseSchema(responseSchema)
                .responseMimeType("application/json")
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .build()

            timed(model) { client.models.generateContent(model.modelId, contents, config) }
        } catch (e: Exception) {
            errorResult(model, e)
        }
    }

    private fun runVisionTaskFreeform(
        model: ModelIds,
        imageBytes: ByteArray,
        imageMimeType: String,
        userPrompt: String,
    ): ModelResult {
        println("  Running ${model.modelId}...")
        return try {
            val contents = listOf(Content.fromParts(
                Part.fromBytes(imageBytes, imageMimeType),
                Part.fromText(userPrompt),
            ))
            val config = GenerateContentConfig.builder()
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
                .build()

            timed(model) { client.models.generateContent(model.modelId, contents, config) }
        } catch (e: Exception) {
            errorResult(model, e)
        }
    }

    private fun runTextTask(
        model: ModelIds,
        systemPrompt: String,
        userPrompt: String,
        responseSchema: Schema,
    ): ModelResult {
        println("  Running ${model.modelId}...")
        return try {
            val contents = listOf(Content.fromParts(Part.fromText(userPrompt)))
            val config = GenerateContentConfig.builder()
                .responseSchema(responseSchema)
                .responseMimeType("application/json")
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .build()

            timed(model) { client.models.generateContent(model.modelId, contents, config) }
        } catch (e: Exception) {
            errorResult(model, e)
        }
    }

    private fun runTextTaskFreeform(
        model: ModelIds,
        userPrompt: String,
    ): ModelResult {
        println("  Running ${model.modelId}...")
        return try {
            val contents = listOf(Content.fromParts(Part.fromText(userPrompt)))
            val config = GenerateContentConfig.builder()
                .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build())
                .build()

            timed(model) { client.models.generateContent(model.modelId, contents, config) }
        } catch (e: Exception) {
            errorResult(model, e)
        }
    }

    private fun timed(model: ModelIds, call: () -> GenerateContentResponse): ModelResult {
        val start = System.currentTimeMillis()
        val response = call()
        val latencyMs = System.currentTimeMillis() - start

        var promptTokens = 0; var outputTokens = 0; var totalTokens = 0
        response.usageMetadata().ifPresent { m ->
            promptTokens = m.promptTokenCount().orElse(0)
            outputTokens = m.candidatesTokenCount().orElse(0)
            totalTokens = m.totalTokenCount().orElse(0)
        }

        return ModelResult(
            modelId = model.modelId,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            outputTokensPerSecond = if (latencyMs > 0) outputTokens * 1000.0 / latencyMs else 0.0,
            estimatedCostUsd = promptTokens * model.inputPricePerMillion / 1_000_000.0 +
                outputTokens * model.outputPricePerMillion / 1_000_000.0,
            rawOutput = response.text() ?: "(no text)",
        )
    }

    private fun errorResult(model: ModelIds, e: Exception): ModelResult {
        println("    ERROR: ${e.message?.take(120)}")
        return ModelResult(model.modelId, -1, 0, 0, 0, 0.0, 0.0, "", e.message ?: e.javaClass.simpleName)
    }

    // ─── Reporting ───────────────────────────────────────────────────

    private fun printComparisonTable(testName: String, results: List<ModelResult>) {
        val sep = "=".repeat(105)
        println("\n$sep")
        println("  $testName")
        println(sep)
        println("  %-28s | %9s | %9s | %7s | %7s | %10s | %s".format(
            "Model", "Latency", "Out tok/s", "Prompt", "Output", "Cost (\$)", "Status"
        ))
        println("  " + "-".repeat(103))
        for (r in results) {
            if (r.error != null) {
                println("  %-28s | %9s | %9s | %7s | %7s | %10s | %s".format(
                    r.modelId, "-", "-", "-", "-", "-", "ERROR"
                ))
            } else {
                println("  %-28s | %7dms | %9.1f | %7d | %7d | %10.6f | %s".format(
                    r.modelId, r.latencyMs, r.outputTokensPerSecond,
                    r.promptTokens, r.outputTokens, r.estimatedCostUsd, "OK"
                ))
            }
        }

        val ok = results.filter { it.error == null }
        if (ok.size >= 2) {
            println(sep)
            val flashLite = ok.find { it.modelId.contains("flash-lite") }
            val gemma = ok.find { it.modelId.contains("gemma") }
            if (flashLite != null && gemma != null) {
                val speedRatio = flashLite.outputTokensPerSecond / gemma.outputTokensPerSecond
                val costRatio = flashLite.estimatedCostUsd / gemma.estimatedCostUsd
                println("  Flash Lite vs Gemma 4: Speed %.1fx faster | Cost %.1fx more expensive".format(speedRatio, costRatio))
            }
        }
        println(sep)
    }

    private fun printQualityOutputs(results: List<ModelResult>) {
        for (r in results) {
            println("\n=== OUTPUT (${r.modelId}) ===")
            if (r.error != null) println("ERROR: ${r.error}")
            else println(r.rawOutput.take(2000) + if (r.rawOutput.length > 2000) "\n... [truncated]" else "")
        }
        println()
    }
}
