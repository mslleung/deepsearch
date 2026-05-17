package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel

import io.deepsearch.domain.agents.BoundingBox
import io.deepsearch.domain.agents.CaptureRegion
import io.deepsearch.domain.agents.ContentExtractionInput
import io.deepsearch.domain.agents.ContentExtractionOutput
import io.deepsearch.domain.agents.IContentExtractionAgent
import io.deepsearch.domain.agents.TableRegionRole
import io.deepsearch.domain.agents.TableSubRegion
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ContentExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IContentExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class BoundingBoxResponse(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int
    )

    @Serializable
    private data class TableSubRegionResponse(
        val boundingBox: BoundingBoxResponse,
        val role: String,
        val description: String
    )

    @Serializable
    private data class CaptureRegionResponse(
        val type: String,
        val relevance: String,
        val boundingBox: BoundingBoxResponse? = null,
        val regions: List<TableSubRegionResponse>? = null
    )

    @Serializable
    private data class ExtractionResponse(
        val captureRegions: List<CaptureRegionResponse>? = null
    )

    private val boundingBoxSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "x1" to Schema.builder().type("INTEGER").description("Left edge (0-1000).").build(),
                "y1" to Schema.builder().type("INTEGER").description("Top edge (0-1000).").build(),
                "x2" to Schema.builder().type("INTEGER").description("Right edge (0-1000).").build(),
                "y2" to Schema.builder().type("INTEGER").description("Bottom edge (0-1000).").build()
            )
        )
        .required(listOf("x1", "y1", "x2", "y2"))
        .build()

    private val tableSubRegionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "boundingBox" to boundingBoxSchema,
                "role" to Schema.builder().type("STRING")
                    .enum_(listOf("header", "data", "context"))
                    .description("Role: 'header' for column/row headers, 'data' for relevant data rows, 'context' for labels/legends/footnotes needed to understand the data.")
                    .build(),
                "description" to Schema.builder().type("STRING")
                    .description("What this sub-region contains (e.g. 'Package name columns: Standard, Comprehensive, Ultra').")
                    .build()
            )
        )
        .required(listOf("boundingBox", "role", "description"))
        .build()

    private val captureRegionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "type" to Schema.builder().type("STRING")
                    .enum_(listOf("element", "table"))
                    .description("'element' for a single content block (paragraph, value, heading). 'table' for tabular/grid data that needs multiple sub-regions (headers + data rows + context) to be understood as a standalone.")
                    .build(),
                "relevance" to Schema.builder().type("STRING")
                    .description("Brief reason why this region is relevant to the query.")
                    .build(),
                "boundingBox" to Schema.builder()
                    .type("OBJECT")
                    .properties(
                        mapOf(
                            "x1" to Schema.builder().type("INTEGER").description("Left edge (0-1000).").build(),
                            "y1" to Schema.builder().type("INTEGER").description("Top edge (0-1000).").build(),
                            "x2" to Schema.builder().type("INTEGER").description("Right edge (0-1000).").build(),
                            "y2" to Schema.builder().type("INTEGER").description("Bottom edge (0-1000).").build()
                        )
                    )
                    .required(listOf("x1", "y1", "x2", "y2"))
                    .nullable(true)
                    .description("Bounding box for type=element. Required when type is 'element'.")
                    .build(),
                "regions" to Schema.builder().type("ARRAY")
                    .items(tableSubRegionSchema)
                    .nullable(true)
                    .description("Sub-regions for type=table. Required when type is 'table'. Must include at least one 'data' region. Include 'header' for column/row names and 'context' for surrounding labels needed to interpret the data.")
                    .build()
            )
        )
        .required(listOf("type", "relevance"))
        .build()

    private val extractionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "captureRegions" to Schema.builder()
                    .type("ARRAY")
                    .items(captureRegionSchema)
                    .description("Bounding boxes around page regions containing data relevant to the query. Provide one region per distinct content block. Use 0-1000 coordinates. Return an empty array if nothing relevant is visible.")
                    .build()
            )
        )
        .required(listOf("captureRegions"))
        .build()

    private val systemInstruction = """
        You are a content region locator. You analyze webpage screenshots to LOCATE areas on the page that contain data relevant to a user's query.

        You do NOT extract or read the content — a separate system handles that. Your ONLY job is to draw accurate bounding boxes (0-1000 coordinates) around regions that contain the answer.

        Each capture region has a type:

        TYPE "element" — for a single content block: a paragraph, a value, a heading with its text.
          Provide a single boundingBox that tightly covers the content.

        TYPE "table" — for tabular, grid-like, or comparison data (including div-based layouts, CSS grids, pricing matrices, feature comparison charts).
          Provide MULTIPLE sub-regions so the table can be understood as a standalone:
          - "header" regions: column headers, row headers, package/plan/tier names, category labels
          - "data" regions: the specific rows/cells that answer the query
          - "context" regions: section titles, legends, footnotes, price labels — anything needed to interpret the data

          Each sub-region has its own bounding box. Together they must capture enough information
          that someone seeing ONLY these regions could fully understand the table data without
          needing to see the rest of the page.

        Guidelines:
        - Point at the area containing the actual answer data — not just nearby headings or section titles alone.
        - Include both the label AND its associated value in the same region when they are visually close.
        - For "element" type, size the box to tightly cover one complete content block.
        - For "table" type, always include a "header" sub-region with column/row names so the data can be interpreted. Include "data" sub-regions for the rows that answer the query. Add "context" sub-regions for any surrounding text needed to understand the data.
        - Use MULTIPLE capture regions when relevant content appears in different areas of the page.
        - If content has already been extracted (shown in EXTRACTED KNOWLEDGE), do NOT re-capture it.
        - Return an empty array if no relevant content is visible on the page.
    """.trimIndent()

    override suspend fun generate(input: ContentExtractionInput): ContentExtractionOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<ExtractionResponse>(this@ContentExtractionAgentGenAiImpl::class.simpleName!! + ".extract") {
                val contentParts = listOf(
                    Part.fromText("PAGE SCREENSHOT:"),
                    Part.fromBytes(input.cleanScreenshot.bytes, input.cleanScreenshot.mimeType.value),
                    Part.fromText(prompt)
                )

                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .temperature(0.5F)
                        .responseSchema(extractionSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        val captureRegions = response.captureRegions?.mapNotNull { r ->
            when (r.type) {
                "element" -> {
                    val box = r.boundingBox ?: return@mapNotNull null
                    val bb = BoundingBox(
                        x1 = box.x1.coerceIn(0, 1000),
                        y1 = box.y1.coerceIn(0, 1000),
                        x2 = box.x2.coerceIn(0, 1000),
                        y2 = box.y2.coerceIn(0, 1000)
                    )
                    if (bb.x2 <= bb.x1 || bb.y2 <= bb.y1) return@mapNotNull null
                    CaptureRegion.Element(relevance = r.relevance, boundingBox = bb)
                }
                "table" -> {
                    val subRegions = r.regions?.mapNotNull subRegionMapping@{ sub ->
                        val role = try {
                            TableRegionRole.valueOf(sub.role.uppercase())
                        } catch (_: IllegalArgumentException) {
                            logger.warn("Unknown table sub-region role: {}", sub.role)
                            return@subRegionMapping null
                        }
                        val bb = BoundingBox(
                            x1 = sub.boundingBox.x1.coerceIn(0, 1000),
                            y1 = sub.boundingBox.y1.coerceIn(0, 1000),
                            x2 = sub.boundingBox.x2.coerceIn(0, 1000),
                            y2 = sub.boundingBox.y2.coerceIn(0, 1000)
                        )
                        if (bb.x2 <= bb.x1 || bb.y2 <= bb.y1) return@subRegionMapping null
                        TableSubRegion(boundingBox = bb, role = role, description = sub.description)
                    } ?: emptyList()
                    if (subRegions.isEmpty()) return@mapNotNull null
                    CaptureRegion.Table(relevance = r.relevance, regions = subRegions)
                }
                else -> {
                    logger.warn("Unknown capture region type: {}", r.type)
                    null
                }
            }
        } ?: emptyList()

        logger.debug("Content extraction: {} capture regions", captureRegions.size)

        return ContentExtractionOutput(
            captureRegions = captureRegions,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: ContentExtractionInput): String = buildString {
        appendLine("QUERY: ${input.query}")

        if (input.extractedRegionContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured — do NOT re-capture these):")
            input.extractedRegionContent.forEach { ec ->
                appendLine("  [${ec.description}]${if (ec.isTable) " (table)" else ""}:")
                appendLine("    ${ec.text}")
            }
        }

        appendLine()
        appendLine("Locate all visible regions on the screenshot that contain data relevant to the QUERY. Return bounding boxes only.")
    }
}
