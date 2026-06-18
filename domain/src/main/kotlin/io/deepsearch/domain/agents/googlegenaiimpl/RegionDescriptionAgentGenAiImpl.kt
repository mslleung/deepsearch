package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.IRegionDescriptionAgent
import io.deepsearch.domain.agents.RegionDescription
import io.deepsearch.domain.agents.RegionDescriptionInput
import io.deepsearch.domain.agents.RegionDescriptionOutput
import io.deepsearch.domain.agents.TableSubRegionDescription
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RegionDescriptionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IRegionDescriptionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class TableSubRegionResponse(
        val description: String,
        val role: String,
        val visualLocation: String
    )

    @Serializable
    private data class RegionDescriptionResponse(
        val description: String,
        val relevance: String,
        val visualLocation: String,
        val containsTable: Boolean = false,
        val tableSubRegions: List<TableSubRegionResponse>? = null
    )

    @Serializable
    private data class DescriptionListResponse(
        val observation: String? = null,
        val regions: List<RegionDescriptionResponse>? = null
    )

    private val tableSubRegionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "description" to Schema.builder().type("STRING")
                    .description("What this sub-region contains (e.g. 'column headers: Plan, Price, Features', 'data rows for Standard and Premium tiers').")
                    .build(),
                "role" to Schema.builder().type("STRING")
                    .description("One of: HEADER (column/row headers), DATA (the data cells/rows), CONTEXT (title, footnotes, or surrounding labels).")
                    .enum_(listOf("HEADER", "DATA", "CONTEXT"))
                    .build(),
                "visualLocation" to Schema.builder().type("STRING")
                    .description("Where this sub-region is within the table: 'top row', 'left column', 'below table title', 'main body', etc.")
                    .build()
            )
        )
        .required(listOf("description", "role", "visualLocation"))
        .build()

    private val regionSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "description" to Schema.builder().type("STRING")
                    .description("What this region contains. Be SPECIFIC: include exact text, numbers, prices, percentages, and product names you can read from the image.")
                    .build(),
                "relevance" to Schema.builder().type("STRING")
                    .description("Why this region is relevant to the user's query.")
                    .build(),
                "visualLocation" to Schema.builder().type("STRING")
                    .description("Where this region is visually on the page. Use relative terms: 'top of page', 'upper third, below the hero section', 'middle of page, in a table', 'lower third, above the footer', etc.")
                    .build(),
                "containsTable" to Schema.builder().type("BOOLEAN")
                    .description("True if this region contains tabular/grid data (comparison table, pricing grid, feature matrix, data table). False for plain text, paragraphs, or single values.")
                    .build(),
                "tableSubRegions" to Schema.builder().type("ARRAY")
                    .items(tableSubRegionSchema)
                    .description("When containsTable is true, describe the sub-parts of the table: HEADER (column/row headers), DATA (the data cells), CONTEXT (title, footnotes). Omit when containsTable is false.")
                    .build()
            )
        )
        .required(listOf("description", "relevance", "visualLocation", "containsTable"))
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
                    .description("Content regions that contain information relevant to the query. Return only relevant regions. Return an empty array if nothing relevant is visible.")
                    .build()
            )
        )
        .required(listOf("observation", "regions"))
        .build()

    private val systemInstruction = """
        You are analyzing a full-page screenshot of a webpage. Your task is to DESCRIBE the content regions that are relevant to a user's query.

        You must READ the actual text, numbers, and data visible in the image. Do NOT guess or infer content — only describe what you can actually see and read.

        For each relevant region, provide:
        1. DESCRIPTION: What the region contains — be specific. Include exact text, numbers, prices, product names, column headers, etc. that you can read. Example: "A pricing card titled 'Payments' showing '2.9% + 30¢ per successful card charge' for domestic transactions."
        2. RELEVANCE: Why this region answers the user's query.
        3. VISUAL LOCATION: Where the region sits on the page using relative terms (top/middle/bottom, left/right, above/below landmarks).
        4. CONTAINS TABLE: Set to true if the region contains tabular/grid data.

        For TABLE regions (containsTable=true), also describe the sub-parts:
        - HEADER: The column/row headers (e.g. "column headers: Plan, Price, Features" at the top row).
        - DATA: The data cells/rows (e.g. "pricing rows for Standard, Premium, Enterprise tiers" in the main body).
        - CONTEXT: Any surrounding labels, titles, or footnotes (e.g. "table title 'Compare Plans'" above the table).
        This helps extract large tables accurately by processing each sub-part separately.

        Guidelines:
        - Focus on regions containing FACTUAL DATA relevant to the query (pricing, features, specifications, etc.).
        - Do NOT generate bounding boxes or coordinates — only textual descriptions.
        - Identify 1-5 regions maximum. Fewer is better if they are precise.
        - Skip navigation bars, footers, cookie banners, hero sections, and decorative elements unless they contain the answer.
        - If content is hidden behind tabs, accordions, or expandable sections, note this in your observation.
        - If content has already been extracted (shown in EXTRACTED KNOWLEDGE), do NOT re-describe those regions.
    """.trimIndent()

    override suspend fun generate(input: RegionDescriptionInput): RegionDescriptionOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildPrompt(input)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<DescriptionListResponse>(this@RegionDescriptionAgentGenAiImpl::class.simpleName!! + ".describe") {
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
                        .responseSchema(responseSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
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

        val descriptions = response.regions?.map { r ->
            RegionDescription(
                description = r.description,
                relevance = r.relevance,
                visualLocation = r.visualLocation,
                containsTable = r.containsTable,
                tableSubRegions = r.tableSubRegions?.map { s ->
                    TableSubRegionDescription(
                        description = s.description,
                        role = s.role,
                        visualLocation = s.visualLocation
                    )
                } ?: emptyList()
            )
        } ?: emptyList()

        logger.debug(
            "Region description: {} regions described, observation={}",
            descriptions.size, response.observation?.take(80)
        )

        return RegionDescriptionOutput(
            descriptions = descriptions,
            observation = response.observation,
            tokenUsage = tokenUsage
        )
    }

    private fun buildPrompt(input: RegionDescriptionInput): String = buildString {
        appendLine("QUERY: ${input.query}")

        if (input.extractedContent.isNotEmpty()) {
            appendLine()
            appendLine("EXTRACTED KNOWLEDGE (already captured — do NOT re-describe these regions):")
            input.extractedContent.forEach { ec ->
                appendLine("  [${ec.description}]${if (ec.isTable) " (table)" else ""}:")
                appendLine("    ${ec.text}")
            }
        }

        appendLine()
        appendLine("Describe the content regions on this webpage that contain information relevant to the QUERY. Read the actual text and data visible in the image.")
    }
}
