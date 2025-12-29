package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.ClassifiedSource
import io.deepsearch.domain.agents.IPreviewClassificationAgent
import io.deepsearch.domain.agents.PreviewClassificationInput
import io.deepsearch.domain.agents.PreviewClassificationOutput
import io.deepsearch.domain.agents.RelevantFact
import io.deepsearch.domain.agents.SourceClassification
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Preview Classification agent that evaluates HTML sources and extracts classified facts.
 * 
 * For each source, the agent:
 * - Extracts facts relevant to the query
 * - Marks whether each fact is from a table/grid
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * 
 * This is a non-streaming agent used before answer synthesis in the preview path.
 */
class PreviewClassificationAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPreviewClassificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val relevantFactSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A fact extracted from the source with metadata")
        .properties(
            mapOf(
                "fact" to Schema.builder()
                    .type("STRING")
                    .description("The extracted fact as a complete, standalone statement")
                    .build(),
                "isInTable" to Schema.builder()
                    .type("BOOLEAN")
                    .description("True if the fact is extracted from a table, grid, or tabular structure")
                    .build(),
                "classification" to Schema.builder()
                    .type("STRING")
                    .description("Source classification: OFFICIAL_LIVING_DOC (main pages like /pricing, /features), OFFICIAL_SNAPSHOT (dated content like /blog, /news), or OTHERS (external reviews, forums, UGC)")
                    .enum_(listOf("OFFICIAL_LIVING_DOC", "OFFICIAL_SNAPSHOT", "OTHERS"))
                    .build()
            )
        )
        .required(listOf("fact", "isInTable", "classification"))
        .build()

    private val classifiedSourceSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A source with its classified relevant facts")
        .properties(
            mapOf(
                "url" to Schema.builder()
                    .type("STRING")
                    .description("The URL of the source")
                    .build(),
                "relevantFacts" to Schema.builder()
                    .type("ARRAY")
                    .items(relevantFactSchema)
                    .description("List of relevant facts extracted from the source")
                    .build()
            )
        )
        .required(listOf("url", "relevantFacts"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Classified sources with extracted facts")
        .properties(
            mapOf(
                "sourceClassifications" to Schema.builder()
                    .type("ARRAY")
                    .items(classifiedSourceSchema)
                    .description("List of sources with their classified facts")
                    .build()
            )
        )
        .required(listOf("sourceClassifications"))
        .build()

    private val systemInstruction = """
        You are a source classification agent. Your job is to extract and classify facts from HTML sources.

        Instructions:
        - For each HTML source, extract facts that are relevant to answering the user's query
        - Each fact should be a complete, standalone statement
        - Mark isInTable=true if the fact comes from:
          - <table>, <tr>, <td>, <th> elements
          - Pricing grids or comparison matrices
          - Repeated <div>-soup patterns that form rows/columns
          - Any tabular data structure
        - Mark isInTable=false for facts from prose paragraphs, headings, or regular text

        - CLASSIFICATION RULES:
          - OFFICIAL_LIVING_DOC: Official main pages meant to stay current
            - /pricing, /features, /about, /home, /products, /docs
            - Pages that represent the current state of the product/company
          - OFFICIAL_SNAPSHOT: Dated content from the same organization
            - /blog, /news, /press, /announcements, /changelog
            - Content with publication dates that may become stale
          - OTHERS: External or user-generated content
            - Third-party reviews, news articles
            - Forums, Reddit, StackOverflow, UGC
            - External comparison sites

        - The URL path is a strong indicator:
          - /pricing, /about, /features, /docs → OFFICIAL_LIVING_DOC
          - /blog/, /news/, /press/ → OFFICIAL_SNAPSHOT
          - External domains, /forum/, /community/ → OTHERS

        - Only extract facts that help answer the query
        - If a source has no relevant facts, return an empty relevantFacts array

        Output Format:
        {
            "sourceClassifications": [
                {
                    "url": "https://example.com/page",
                    "relevantFacts": [
                        {
                            "fact": "The product costs $99/month",
                            "isInTable": true,
                            "classification": "OFFICIAL_LIVING_DOC"
                        }
                    ]
                }
            ]
        }
    """.trimIndent()

    @Serializable
    private data class LlmRelevantFact(
        val fact: String,
        val isInTable: Boolean,
        val classification: String
    )

    @Serializable
    private data class LlmClassifiedSource(
        val url: String,
        val relevantFacts: List<LlmRelevantFact>
    )

    @Serializable
    private data class ClassificationResponse(
        val sourceClassifications: List<LlmClassifiedSource>
    )

    override suspend fun generate(input: PreviewClassificationInput): PreviewClassificationOutput {
        logger.debug(
            "Classifying preview sources for query: '{}', sources: {}",
            input.query,
            input.htmlSources.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.htmlSources.isEmpty()) {
            logger.debug("Empty HTML sources, returning empty result")
            return PreviewClassificationOutput(
                sourceClassifications = emptyList(),
                tokenUsage = tokenUsage
            )
        }

        val userPrompt = buildUserPrompt(input)

        val response = retryLlmCall<ClassificationResponse>(this::class.simpleName!!) {
            val result = client.models.generateContent(
                modelId,
                userPrompt,
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

            // Extract token usage
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

        logger.debug(
            "[{}] response: {}",
            PreviewClassificationAgentGenAiImpl::class.simpleName,
            response
        )

        val sourceClassifications = response.sourceClassifications.map { llmSource ->
            ClassifiedSource(
                url = llmSource.url,
                relevantFacts = llmSource.relevantFacts.map { llmFact ->
                    RelevantFact(
                        fact = llmFact.fact,
                        isInTable = llmFact.isInTable,
                        classification = parseClassification(llmFact.classification)
                    )
                }
            )
        }

        logger.debug(
            "Preview classification complete: {} sources, {} total facts",
            sourceClassifications.size,
            sourceClassifications.sumOf { it.relevantFacts.size }
        )

        return PreviewClassificationOutput(
            sourceClassifications = sourceClassifications,
            tokenUsage = tokenUsage
        )
    }

    private fun parseClassification(value: String): SourceClassification {
        return when (value.uppercase()) {
            "OFFICIAL_LIVING_DOC" -> SourceClassification.OFFICIAL_LIVING_DOC
            "OFFICIAL_SNAPSHOT" -> SourceClassification.OFFICIAL_SNAPSHOT
            else -> SourceClassification.OTHERS
        }
    }

    private fun buildUserPrompt(input: PreviewClassificationInput): String {
        return buildString {
            // Include current date for temporal context
            appendLine("# Current Date")
            appendLine(java.time.LocalDate.now().toString())
            appendLine()

            appendLine("# Query")
            appendLine(input.query)
            appendLine()

            appendLine("# HTML Sources to Classify")
            input.htmlSources.forEachIndexed { index, source ->
                appendLine("## Source ${index + 1}")
                appendLine("URL: ${source.url}")
                if (source.title != null) {
                    appendLine("Title: ${source.title}")
                }
                if (source.description != null) {
                    appendLine("Description: ${source.description}")
                }
                appendLine()
                appendLine("### HTML Content")
                appendLine("```html")
                appendLine(source.cleanedHtml)
                appendLine("```")
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }
}

