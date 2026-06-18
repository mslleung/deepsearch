package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.IVisualContentExtractionAgent
import io.deepsearch.domain.agents.VisualContentExtractionInput
import io.deepsearch.domain.agents.VisualContentExtractionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.TableMarkdownUtils
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class VisualContentExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IVisualContentExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Serializable
    private data class ExtractionResponse(
        val markdown: String
    )

    private val responseSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("Extracted content as clean markdown. Use HTML <table> tags for tabular data.")
                    .build()
            )
        )
        .required(listOf("markdown"))
        .build()

    private val systemInstruction = """
        You extract content from a cropped webpage region image as clean markdown.
        
        Your task:
        1. Read the content visible in this image crop.
        2. Focus on content relevant to the given query — omit navigation, decorative, or clearly irrelevant elements.
        3. Preserve ALL numbers, prices, percentages, and special characters exactly as shown.
        
        Output format:
        - For regular text: output markdown directly (headings, paragraphs, lists, bold/italic).
        - For tabular/grid data: output an HTML <table> with <thead>, <tbody>, <tr>, <th>, <td>. 
          Use colspan/rowspan for merged cells. This will be programmatically converted to markdown.
        - For mixed content: combine markdown text and HTML tables as needed.
        
        Critical rules:
        - NEVER fabricate or guess content that is not clearly visible in the image.
        - Preserve the exact wording, numbers, and formatting shown.
        - If text is partially cut off at crop edges, include what is readable and note "[truncated]".
        - Keep the output concise — extract the relevant content, not a verbose description of it.
    """.trimIndent()

    override suspend fun generate(input: VisualContentExtractionInput): VisualContentExtractionOutput {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val prompt = buildString {
            appendLine("QUERY: ${input.query}")
            appendLine("REGION: ${input.regionDescription}")
            appendLine()
            appendLine("Extract the relevant content from this webpage region image.")
        }

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<ExtractionResponse>(this@VisualContentExtractionAgentGenAiImpl::class.simpleName!! + ".extract") {
                val contentParts = listOf(
                    Part.fromText("WEBPAGE REGION IMAGE:"),
                    Part.fromBytes(input.regionImage, input.imageMimeType.value),
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
                        .maxOutputTokens(8192)
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

        val markdown = TableMarkdownUtils.transformHTMLTablesToMarkdown(response.markdown).trim()

        logger.debug(
            "Visual extraction ({} chars): {}",
            markdown.length, markdown.take(150)
        )

        return VisualContentExtractionOutput(
            markdown = markdown,
            tokenUsage = tokenUsage
        )
    }
}
