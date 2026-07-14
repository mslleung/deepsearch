package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.ContentRegionLocatorInput
import io.deepsearch.domain.agents.ContentRegionLocatorOutput
import io.deepsearch.domain.agents.IContentRegionLocatorAgent
import io.deepsearch.domain.agents.IdentifiedRegion
import io.deepsearch.domain.agents.IdentifiedTableSubRegion
import io.deepsearch.domain.agents.TableRegionRole
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ContentRegionLocatorAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IContentRegionLocatorAgent {

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
        val containsTable: Boolean = false,
        val tableSubRegions: List<SubRegionResponse>? = null
    )

    @Serializable
    private data class LocatorResponse(
        val observation: String? = null,
        val regions: List<RegionResponse>? = null
    )

    private val subRegionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "description" to Schema.builder().type("STRING")
                    .description("What this sub-region contains (e.g. 'column headers: Plan, Price, Features').")
                    .build(),
                "role" to Schema.builder().type("STRING")
                    .description("One of: HEADER (column/row headers), DATA (data cells/rows), CONTEXT (title, footnotes, surrounding labels).")
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
                    .description("What this region contains. Be SPECIFIC: include exact text, numbers, prices, product names you can read from the image.")
                    .build(),
                "relevance" to Schema.builder().type("STRING")
                    .description("Why this region is relevant to the user's query.")
                    .build(),
                "box_2d" to Schema.builder().type("ARRAY")
                    .items(Schema.builder().type("INTEGER").build())
                    .description("Bounding box as [ymin, xmin, ymax, xmax] normalized to 0-1000. Origin (0,0) is top-left. Be generous — include a small margin around the content.")
                    .build(),
                "containsTable" to Schema.builder().type("BOOLEAN")
                    .description("True if this region contains tabular/grid data (comparison table, pricing grid, feature matrix). False for plain text or single values.")
                    .build(),
                "tableSubRegions" to Schema.builder().type("ARRAY")
                    .items(subRegionSchema)
                    .description("When containsTable is true, locate each sub-part: HEADER (column/row headers), DATA (data cells), CONTEXT (title, footnotes). Omit when containsTable is false.")
                    .build()
            )
        )
        .required(listOf("description", "relevance", "box_2d", "containsTable"))
        .propertyOrdering(listOf("description", "relevance", "box_2d", "containsTable", "tableSubRegions"))
        .build()

    private val responseSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "observation" to Schema.builder()
                    .type("STRING")
                    .description("Brief overview of the page structure and any UI patterns that hide content (tabs, accordions, carousels).")
                    .build(),
                "regions" to Schema.builder()
                    .type("ARRAY")
                    .items(regionSchema)
                    .description("Content regions relevant to the query, each with a description and bounding box. Return 1-5 regions. Return an empty array if nothing relevant is visible.")
                    .build()
            )
        )
        .required(listOf("observation", "regions"))
        .build()

    private val systemInstruction = """
        You are analyzing a full-page screenshot of a webpage. Your task is to IDENTIFY and LOCATE content regions relevant to a user's query.

        For each relevant region, you must:
        1. READ the actual text visible in the image — do NOT guess or infer content.
        2. DESCRIBE what the region contains with specifics: exact text, numbers, prices, product names, column headers.
        3. LOCATE it with a bounding box (box_2d) normalized to 0-1000.

        Output box_2d as [ymin, xmin, ymax, xmax] where (0,0) is top-left and (1000,1000) is bottom-right.
        Make bounding boxes generous — it is better to include surrounding context than to clip content.

        For TABLE regions (containsTable=true), also locate sub-parts with individual bounding boxes:
        - HEADER: column/row headers
        - DATA: the data cells/rows
        - CONTEXT: title, footnotes, surrounding labels

        Guidelines:
        - Identify 1-5 regions maximum. Fewer precise regions are better than many vague ones.
        - Focus on regions containing FACTUAL DATA relevant to the query (pricing, features, specifications).
        - Skip navigation bars, footers, cookie banners, hero sections, and decorative elements.
        - If content is hidden behind tabs, accordions, or expandable sections, note this in observation.
        - If content has already been extracted (shown in EXTRACTED KNOWLEDGE), do NOT re-identify those regions.
    """.trimIndent()

    override suspend fun generate(input: ContentRegionLocatorInput): ContentRegionLocatorOutput {
        val modelId = ModelIds.GEMINI_3_5_FLASH.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<LocatorResponse>(this@ContentRegionLocatorAgentGenAiImpl::class.simpleName!! + ".locate") {
                val contentParts = listOf(
                    Part.fromText("FULL PAGE SCREENSHOT:"),
                    Part.fromBytes(input.screenshot.bytes, input.screenshot.mimeType.value),
                    Part.fromText(prompt)
                )
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*contentParts.toTypedArray())),
                    GenerateContentConfig.builder()
                        .temperature(1.0f)
                        .responseSchema(responseSchema)
                        .responseMimeType("application/json")
                        .maxOutputTokens(4096)
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

        val regions = response.regions?.mapNotNull { r ->
            val box = r.box_2d
            if (box.size < 4) return@mapNotNull null
            val ymin = box[0].coerceIn(0, 1000)
            val xmin = box[1].coerceIn(0, 1000)
            val ymax = box[2].coerceIn(0, 1000)
            val xmax = box[3].coerceIn(0, 1000)

            val tableSubRegions = if (r.containsTable) {
                r.tableSubRegions?.mapNotNull { sr ->
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
            } else emptyList()

            IdentifiedRegion(
                description = r.description,
                relevance = r.relevance,
                x1 = xmin, y1 = ymin, x2 = xmax, y2 = ymax,
                containsTable = r.containsTable,
                tableSubRegions = tableSubRegions
            )
        }?.filter { it.x2 > it.x1 && it.y2 > it.y1 } ?: emptyList()

        logger.debug(
            "Content region locator: {} regions identified, observation={}",
            regions.size, response.observation?.take(80)
        )

        return ContentRegionLocatorOutput(
            regions = regions,
            observation = response.observation,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: ContentRegionLocatorInput): String = buildString {
        appendLine("QUERY: ${input.query}")

        if (input.extractedContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured — do NOT re-identify these regions):")
            input.extractedContent.forEach { ec ->
                appendLine("  [${ec.description}]${if (ec.isTable) " (table)" else ""}:")
                appendLine("    ${ec.text}")
            }
        }

        appendLine()
        appendLine("Identify and locate the content regions on this webpage that are relevant to the QUERY. For each region, describe what it contains and provide a bounding box.")
    }
}
