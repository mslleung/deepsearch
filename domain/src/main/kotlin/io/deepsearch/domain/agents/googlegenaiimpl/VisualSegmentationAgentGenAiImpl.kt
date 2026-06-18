package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ExtractedContent
import io.deepsearch.domain.agents.IVisualSegmentationAgent
import io.deepsearch.domain.agents.IdentifiedRegion
import io.deepsearch.domain.agents.IdentifiedTableSubRegion
import io.deepsearch.domain.agents.RegionDescription
import io.deepsearch.domain.agents.TableRegionRole
import io.deepsearch.domain.agents.VisualSegmentationInput
import io.deepsearch.domain.agents.VisualSegmentationOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class VisualSegmentationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IVisualSegmentationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class SubRegionResponse(
        val description: String,
        val role: String,
        val box_2d: List<Int>
    )

    @Serializable
    private data class RegionResponse(
        val description: String,
        val relevance: String,
        val box_2d: List<Int>,
        val tableSubRegions: List<SubRegionResponse>? = null
    )

    @Serializable
    private data class SegmentationResponse(
        val observation: String? = null,
        val regions: List<RegionResponse>? = null
    )

    private val subRegionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "description" to Schema.builder().type("STRING")
                    .description("What this sub-region contains.")
                    .build(),
                "role" to Schema.builder().type("STRING")
                    .description("One of: HEADER, DATA, CONTEXT.")
                    .enum_(listOf("HEADER", "DATA", "CONTEXT"))
                    .build(),
                "box_2d" to Schema.builder().type("ARRAY")
                    .items(Schema.builder().type("INTEGER").build())
                    .description("Bounding box as [ymin, xmin, ymax, xmax] normalized to 0-1000.")
                    .build()
            )
        )
        .required(listOf("description", "role", "box_2d"))
        .build()

    private val regionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "description" to Schema.builder().type("STRING")
                    .description("What this region contains (e.g. 'Payments pricing card', 'FAQ accordion section').")
                    .build(),
                "relevance" to Schema.builder().type("STRING")
                    .description("Why this region is relevant to the query.")
                    .build(),
                "box_2d" to Schema.builder().type("ARRAY")
                    .items(Schema.builder().type("INTEGER").build())
                    .description("Bounding box as [ymin, xmin, ymax, xmax] normalized to 0-1000. Origin (0,0) is top-left.")
                    .build(),
                "tableSubRegions" to Schema.builder().type("ARRAY")
                    .items(subRegionSchema)
                    .description("When the region is a table, locate each sub-part (HEADER, DATA, CONTEXT) with its own bounding box. Omit for non-table regions.")
                    .build()
            )
        )
        .required(listOf("description", "relevance", "box_2d"))
        .build()

    private val segmentationSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "observation" to Schema.builder()
                    .type("STRING")
                    .description("Note any UI patterns that hide content (tabs, accordions, carousels) and what content might be revealed by interacting with them.")
                    .build(),
                "regions" to Schema.builder()
                    .type("ARRAY")
                    .items(regionSchema)
                    .description("Major content regions on the page that contain information relevant to the query. Return only regions with relevant content. Return an empty array if nothing relevant is visible.")
                    .build()
            )
        )
        .required(listOf("observation", "regions"))
        .build()

    private val systemInstruction = """
        You are a webpage layout analyzer. You examine full-page screenshots and identify the major content regions that are relevant to a user's query.
        
        Think of regions the way a human scans a webpage:
        - "The pricing card for Payments" (a visual card/section with pricing info)
        - "The feature comparison table" (a grid or table comparing products)
        - "The FAQ section" (a list of expandable questions)
        - "The billing details section" (a paragraph or card about billing)
        
        NOT individual paragraphs, table cells, or small UI elements.
        
        Guidelines:
        - Identify 5-15 MAJOR content regions — large, visually distinct sections.
        - Only include regions that contain content RELEVANT to the query.
        - Return box_2d as [ymin, xmin, ymax, xmax] normalized to 0-1000. Origin (0,0) is top-left.
        - Make bounding boxes generous — it's better to include a bit of surrounding context than to cut off content.
        - Skip navigation bars, footers, cookie banners, and decorative elements.
        - If content is hidden behind tabs, accordions, or expandable sections, note this in the observation.
        - If content has already been extracted (shown in EXTRACTED KNOWLEDGE), do NOT re-identify those regions.
        - Return an empty regions array if no relevant content is visible.
    """.trimIndent()

    private val guidedSystemInstruction = """
        You are a webpage region locator. You receive TEXTUAL DESCRIPTIONS of content regions that have already been identified as relevant, and your task is to LOCATE them on the screenshot by generating accurate bounding boxes.

        You are NOT identifying what is relevant — that has already been done. You are finding WHERE each described region is on the page.

        Guidelines:
        - For each described region, find its visual location on the screenshot and return a bounding box.
        - Return box_2d as [ymin, xmin, ymax, xmax] normalized to 0-1000. Origin (0,0) is top-left.
        - Make bounding boxes generous — include the full region with a small margin.
        - Match descriptions carefully: use the visual location hints (e.g. "middle of page, below hero") and content details (e.g. specific text, numbers) to locate the exact section.
        - If a described region cannot be found on the screenshot, skip it.
        - Return an empty regions array only if NONE of the described regions can be located.
    """.trimIndent()

    override suspend fun generate(input: VisualSegmentationInput): VisualSegmentationOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val isGuided = input.regionDescriptions.isNotEmpty()
        val prompt = buildPrompt(input)
        val activeSystemInstruction = if (isGuided) guidedSystemInstruction else systemInstruction

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SegmentationResponse>(this@VisualSegmentationAgentGenAiImpl::class.simpleName!! + ".segment") {
                val contentParts = listOf(
                    Part.fromText("FULL PAGE SCREENSHOT:"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(prompt)
                )

                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(segmentationSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(activeSystemInstruction)))
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

        val regions = response.regions?.mapNotNull { r ->
            val box = r.box_2d
            if (box.size < 4) return@mapNotNull null
            val ymin = box[0].coerceIn(0, 1000)
            val xmin = box[1].coerceIn(0, 1000)
            val ymax = box[2].coerceIn(0, 1000)
            val xmax = box[3].coerceIn(0, 1000)

            val matchedDescription = input.regionDescriptions.firstOrNull { desc ->
                desc.description.equals(r.description, ignoreCase = true)
                    || desc.relevance.equals(r.relevance, ignoreCase = true)
            }

            val tableSubRegions = r.tableSubRegions?.mapNotNull { sr ->
                val srBox = sr.box_2d
                if (srBox.size < 4) return@mapNotNull null
                val srYmin = srBox[0].coerceIn(0, 1000)
                val srXmin = srBox[1].coerceIn(0, 1000)
                val srYmax = srBox[2].coerceIn(0, 1000)
                val srXmax = srBox[3].coerceIn(0, 1000)
                if (srXmax <= srXmin || srYmax <= srYmin) return@mapNotNull null
                val role = try {
                    TableRegionRole.valueOf(sr.role.uppercase())
                } catch (_: IllegalArgumentException) {
                    TableRegionRole.DATA
                }
                IdentifiedTableSubRegion(
                    role = role,
                    description = sr.description,
                    x1 = srXmin, y1 = srYmin, x2 = srXmax, y2 = srYmax
                )
            } ?: emptyList()

            IdentifiedRegion(
                description = r.description,
                relevance = r.relevance,
                x1 = xmin,
                y1 = ymin,
                x2 = xmax,
                y2 = ymax,
                containsTable = matchedDescription?.containsTable ?: false,
                tableSubRegions = tableSubRegions
            )
        }?.filter { it.x2 > it.x1 && it.y2 > it.y1 } ?: emptyList()

        logger.debug(
            "Visual segmentation: {} regions identified, observation={}",
            regions.size, response.observation?.take(80)
        )

        return VisualSegmentationOutput(
            regions = regions,
            observation = response.observation,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: VisualSegmentationInput): String = buildString {
        appendLine("QUERY: ${input.query}")

        if (input.regionDescriptions.isNotEmpty()) {
            appendLine()
            appendLine("DESCRIBED REGIONS (locate these on the screenshot and return bounding boxes):")
            input.regionDescriptions.forEachIndexed { idx, desc ->
                appendLine("  Region ${idx + 1}:")
                appendLine("    Content: ${desc.description}")
                appendLine("    Relevance: ${desc.relevance}")
                appendLine("    Visual location: ${desc.visualLocation}")
                if (desc.containsTable && desc.tableSubRegions.isNotEmpty()) {
                    appendLine("    TABLE — also locate each sub-region with its own bounding box:")
                    desc.tableSubRegions.forEach { sr ->
                        appendLine("      - ${sr.role}: ${sr.description} (at: ${sr.visualLocation})")
                    }
                }
            }
        }

        if (input.extractedRegionContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured — do NOT re-identify these regions):")
            input.extractedRegionContent.forEach { ec ->
                appendLine("  [${ec.description}]${if (ec.isTable) " (table)" else ""}:")
                appendLine("    ${ec.text}")
            }
        }

        appendLine()
        if (input.regionDescriptions.isNotEmpty()) {
            appendLine("Locate the DESCRIBED REGIONS on this screenshot and return bounding boxes for each one.")
            if (input.regionDescriptions.any { it.containsTable && it.tableSubRegions.isNotEmpty() }) {
                appendLine("For TABLE regions, also return tableSubRegions with individual bounding boxes for each sub-part (HEADER, DATA, CONTEXT).")
            }
        } else {
            appendLine("Identify the major content regions on this webpage that contain information relevant to the QUERY. Return bounding boxes for each relevant region.")
        }
    }
}
