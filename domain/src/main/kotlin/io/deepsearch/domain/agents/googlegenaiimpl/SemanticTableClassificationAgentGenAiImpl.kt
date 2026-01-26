package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ISemanticTableClassificationAgent
import io.deepsearch.domain.agents.SemanticTableClassificationBatchResult
import io.deepsearch.domain.agents.SemanticTableClassificationInput
import io.deepsearch.domain.agents.SnippetClassification
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Lightweight LLM agent that classifies markdown tables to determine their content type.
 * 
 * This agent receives markdown tables that were programmatically converted from semantic
 * HTML `<table>` elements and classifies them to detect:
 * - COOKIE_DECLARATION_TABLE: Cookie consent tables (to be removed)
 * - HIDDEN_MOBILE_LAYOUT: Duplicate mobile content (to be removed)
 * - TABLE: Regular data tables (to be kept)
 * - OTHERS: Non-tabular content (to be kept)
 * 
 * The classification is much faster than full table interpretation since:
 * 1. The markdown is already generated (no conversion needed)
 * 2. We only need classification, not content transformation
 */
class SemanticTableClassificationAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ISemanticTableClassificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Classification results for markdown tables")
        .properties(
            mapOf(
                "classifications" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of classifications, one per input table, in the same order")
                    .items(
                        Schema.builder()
                            .type("STRING")
                            .enum_(listOf("TABLE", "COOKIE_DECLARATION_TABLE", "HIDDEN_MOBILE_LAYOUT", "OTHERS"))
                            .description("Classification for a single table")
                            .build()
                    )
                    .build()
            )
        )
        .required(listOf("classifications"))
        .build()

    private val systemInstruction = """
        You are classifying markdown tables to determine their content type.
        These tables were converted from semantic HTML <table> elements.
        
        Classify each table into one of these categories:
        
        - TABLE: Regular data tables containing useful information such as:
          * Pricing tables, product comparisons, specification tables
          * Data tables with statistics, measurements, or records
          * Feature comparison tables, compatibility matrices
          * Schedule tables, timetables, calendars
          
        - COOKIE_DECLARATION_TABLE: Cookie consent/declaration tables that are legal boilerplate:
          * Lists cookies with columns like: Name, Provider, Purpose, Expiry, Type
          * Found in privacy policies or cookie consent sections
          * Contains technical cookie identifiers (e.g., "_ga", "PHPSESSID", etc.)
          * These should be REMOVED from the output
          
        - HIDDEN_MOBILE_LAYOUT: Duplicate content for mobile layouts:
          * Same content as visible desktop version but formatted for mobile
          * Tables with responsive/mobile-specific data
          * Usually hidden via CSS but captured during extraction
          * These should be REMOVED from the output
          
        - OTHERS: Content that is not actually tabular:
          * Navigation menus structured as tables
          * Form layouts, UI component containers
          * Tables used purely for visual layout (not data)
        
        Output a JSON object with "classifications" array containing one classification
        per input table, in the exact same order as the input.
        
        Example input:
        Table 0:
        | Plan | Price | Features |
        | --- | --- | --- |
        | Basic | $10/mo | 5 users |
        | Pro | $25/mo | 25 users |
        
        Table 1:
        | Cookie | Provider | Purpose | Expiry |
        | --- | --- | --- | --- |
        | _ga | Google | Analytics | 2 years |
        
        Example output:
        {"classifications": ["TABLE", "COOKIE_DECLARATION_TABLE"]}
    """.trimIndent()

    @Serializable
    private data class ClassificationResponse(
        val classifications: List<String>
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun classifyTables(inputs: List<SemanticTableClassificationInput>): SemanticTableClassificationBatchResult {
        if (inputs.isEmpty()) {
            return SemanticTableClassificationBatchResult(
                classifications = emptyList(),
                tokenUsage = TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
            )
        }

        // Build prompt with numbered tables
        val prompt = buildString {
            appendLine("Classify the following ${inputs.size} markdown table(s):")
            appendLine()
            inputs.forEachIndexed { idx, input ->
                appendLine("Table $idx:")
                if (input.auxiliaryInfo.isNotBlank()) {
                    appendLine("Context: ${input.auxiliaryInfo}")
                }
                appendLine(input.markdownTable)
                appendLine()
            }
            appendLine("Output the classifications in JSON format.")
        }

        logger.debug("Classifying {} semantic tables", inputs.size)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<ClassificationResponse>(this@SemanticTableClassificationAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    prompt,
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
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

        // Parse classifications and ensure we have one per input
        val classifications = response.classifications.map { SnippetClassification.fromString(it) }
        
        // Pad or truncate to match input size (defensive)
        val finalClassifications = if (classifications.size != inputs.size) {
            logger.warn(
                "Classification count mismatch: got {} for {} inputs, padding/truncating",
                classifications.size, inputs.size
            )
            if (classifications.size < inputs.size) {
                classifications + List(inputs.size - classifications.size) { SnippetClassification.TABLE }
            } else {
                classifications.take(inputs.size)
            }
        } else {
            classifications
        }

        logger.debug(
            "Classified {} tables: {} TABLE, {} COOKIE, {} HIDDEN, {} OTHERS",
            finalClassifications.size,
            finalClassifications.count { it == SnippetClassification.TABLE },
            finalClassifications.count { it == SnippetClassification.COOKIE_DECLARATION_TABLE },
            finalClassifications.count { it == SnippetClassification.HIDDEN_MOBILE_LAYOUT },
            finalClassifications.count { it == SnippetClassification.OTHERS }
        )

        return SemanticTableClassificationBatchResult(
            classifications = finalClassifications,
            tokenUsage = tokenUsage
        )
    }
}
