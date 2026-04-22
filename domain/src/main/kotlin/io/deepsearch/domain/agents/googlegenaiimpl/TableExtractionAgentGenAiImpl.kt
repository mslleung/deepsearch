package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ITableExtractionAgent
import io.deepsearch.domain.agents.TableExtractionInput
import io.deepsearch.domain.agents.TableExtractionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.TableMarkdownUtils
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Table extraction agent specialized for extracting tabular data from images.
 *
 * This agent processes images known to contain tables and extracts
 * the data in HTML table format, which is then converted to markdown.
 *
 * Each image is processed individually in its own LLM call to maximize
 * extraction quality, while processing multiple images in parallel for throughput.
 */
class TableExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ITableExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_PIXEL_COUNT = 33_000_000L // ~33 million pixels (e.g., 6000×5500)
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("table extracted from image as text")
        .properties(
            mapOf(
                "text" to Schema.builder()
                    .type("STRING")
                    .description("text representation of the image with tables in HTML format")
                    .build()
            )
        )
        .required(listOf("text"))
        .build()

    private val systemInstruction = """
        You are given an image that contains a table. Your task is to extract all content including the table.
        
        Instructions:
        - Extract all text present in the image
        - When you see data arranged in table format, you must convert it to HTML table format using <table>, <tr>, <td> tags
        - For table merged cells, use colspan/rowspan attributes (e.g., <td colspan="2">)
        - Make sure the table dimension correctly reflects what is seen in the image
        
        Example extracted text for an image with table: 
        You MUST output the table in HTML format, such as:
        <table>
            <thead>
                <tr>
                    <td></td>
                    <td>be@me. 透明牙箍 <br/> 牙齒更整齊</td>
                    <td>be@me. PRO <br/> 解決更複雜牙齒問題</td>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td>原價</td>
                    <td>${'$'}16,800</td>
                    <td>${'$'}28,620</td>
                </tr>
                <tr>
                    <td>網上預約折扣</td>
                    <td colspan="2">減${'$'}1,820</td>
                </tr>
                <tr>
                    <td>學生箍牙優惠</td>
                    <td colspan="2">再減 ${'$'}500</td>
                </tr>
                <tr>
                    <td>優惠價</td>
                    <td>${'$'}14,480</td>
                    <td>${'$'}26,300</td>
                </tr>
            </tbody>
        </table>
        
        For images with both text and tables, combine them:
        Lorem ipsum is a text placeholder
        <table>...</table>
        More text after the table
        
        Expected output shape:
        {
            "text": string
        }
    """.trimIndent()

    @Serializable
    private data class SingleTableExtractionResponse(
        val text: String
    )

    override suspend fun generate(input: TableExtractionInput): TableExtractionOutput {
        logger.debug(
            "Extracting tables from {} images (processing each image individually in parallel)",
            input.images.size
        )

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        val emptyTokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.images.isEmpty()) {
            return TableExtractionOutput(
                extractions = emptyList(),
                tokenUsage = emptyTokenUsage
            )
        }

        // Process each image individually, in parallel if multiple images
        return if (input.images.size == 1) {
            // Single image - process directly
            val (extraction, tokenUsage) = processSingleImage(input.images[0], 0)
            TableExtractionOutput(
                extractions = listOf(extraction),
                tokenUsage = tokenUsage
            )
        } else {
            // Multiple images - process in parallel
            processImagesInParallel(input.images)
        }
    }

    /**
     * Process multiple images in parallel, each with its own LLM call.
     */
    private suspend fun processImagesInParallel(images: List<TableExtractionInput.ImageItem>): TableExtractionOutput =
        coroutineScope {
            logger.debug("Processing {} images in parallel for table extraction", images.size)

            // Process all images in parallel
            val results = images.mapIndexed { index, image ->
                async {
                    processSingleImage(image, index)
                }
            }.awaitAll()

            // Combine results in order
            val allExtractions = results.map { it.first }
            val aggregatedTokenUsage =
                results.fold(TokenUsageMetrics.empty(ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId)) { acc, (_, tokenUsage) ->
                    TokenUsageMetrics(
                        modelName = acc.modelName,
                        promptTokens = acc.promptTokens + tokenUsage.promptTokens,
                        outputTokens = acc.outputTokens + tokenUsage.outputTokens,
                        totalTokens = acc.totalTokens + tokenUsage.totalTokens
                    )
                }

            TableExtractionOutput(
                extractions = allExtractions,
                tokenUsage = aggregatedTokenUsage
            )
        }

    /**
     * Process a single image with a dedicated LLM call.
     * Returns a Pair of (extraction, tokenUsage) for aggregation.
     */
    private suspend fun processSingleImage(
        image: TableExtractionInput.ImageItem,
        imageIndex: Int
    ): Pair<TableExtractionOutput.TextExtraction, TokenUsageMetrics> {
        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Check if image is too large
        if (isImageTooLarge(image.bytes, image.mimeType)) {
            logger.error(
                "Image at position {} is too large (resolution exceeds {} pixels); returning empty string",
                imageIndex,
                MAX_PIXEL_COUNT
            )
            return TableExtractionOutput.TextExtraction(extractedText = "") to tokenUsage
        }

        // Build content with single image
        val contentParts = listOf(
            Part.fromBytes(image.bytes, image.mimeType.value),
            Part.fromText("Extract all content from this image, converting any tables to HTML format")
        )

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<SingleTableExtractionResponse>(this@TableExtractionAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(*(contentParts.toTypedArray()))),
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
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
        }

        // Transform HTML tables to markdown
        val extractedText = response.text.let { rawText ->
            if (rawText.isNotBlank()) {
                transformHTMLTablesToMarkdown(rawText).trim()
            } else {
                null
            }
        }

        if (extractedText != null && extractedText.isNotBlank()) {
            logger.debug("Table extracted from image at position {}: {}", imageIndex, extractedText.take(200))
        } else {
            logger.debug(
                "No table content extracted from image at position {} ({} bytes)",
                imageIndex,
                image.bytes.size
            )
        }

        return TableExtractionOutput.TextExtraction(extractedText = extractedText) to tokenUsage
    }

    /**
     * Detects if the provided image resolution is ridiculously large.
     * Such images could cause performance issues or errors.
     */
    private fun isImageTooLarge(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") mimeType: ImageMimeType): Boolean {
        return try {
            val bufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
            val width = bufferedImage.width
            val height = bufferedImage.height
            val pixelCount = width.toLong() * height.toLong()
            pixelCount > MAX_PIXEL_COUNT
        } catch (e: Exception) {
            logger.warn("Failed to check image size, proceeding with LLM", e)
            false
        }
    }

    private fun transformHTMLTablesToMarkdown(text: String): String =
        TableMarkdownUtils.transformHTMLTablesToMarkdown(text)

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalEncodingApi::class)
    override fun prepareBatchRequest(
        requestId: String,
        image: TableExtractionInput.ImageItem
    ): BatchContentRequest {
        return BatchContentRequest(
            requestId = requestId,
            modelId = ModelIds.GEMINI_3_1_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = systemInstruction,
            userPrompt = "Extract all content from this image, converting any tables to HTML format",
            imageData = Base64.encode(image.bytes),
            imageMimeType = image.mimeType.value,
            temperature = 1.0f
        ).withSchema(outputSchema) // Use same schema as interactive mode
    }

    override fun parseBatchResponse(responseText: String): String? {
        return try {
            val response = batchJson.decodeFromString<SingleTableExtractionResponse>(responseText)
            if (response.text.isNotBlank()) {
                transformHTMLTablesToMarkdown(response.text).trim()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            null
        }
    }
}
