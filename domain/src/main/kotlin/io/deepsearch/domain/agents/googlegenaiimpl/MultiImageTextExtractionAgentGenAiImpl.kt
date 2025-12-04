package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IMultiImageTextExtractionAgent
import io.deepsearch.domain.agents.MultiImageTextExtractionInput
import io.deepsearch.domain.agents.MultiImageTextExtractionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Multimodal multi-image text extraction agent.
 *
 * Given multiple images that may contain text (including tables), extract the text
 * and preserve structure (especially for tables).
 * This agent processes each image individually in its own LLM call to maximize
 * extraction quality, while processing multiple images in parallel for throughput.
 */
class MultiImageTextExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IMultiImageTextExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val MAX_PIXEL_COUNT = 33_000_000L // ~33 million pixels (e.g., 6000×5500)
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Extracted text from a single image")
        .properties(
            mapOf(
                "extractedText" to Schema.builder()
                    .type("STRING")
                    .description("Text extracted from the image, preserving structure (especially tables)")
                    .nullable(true)
                    .build()
            )
        )
        .required(listOf("extractedText"))
        .build()

    private val systemInstruction = """
        You are given an image that may contain text. Your task is to extract all visible text from the image.
        
        Instructions:
        - Extract all text present in the image, with reasonable line breaks
        - IMPORTANT: When you see data arranged in rows and columns (a table), you MUST convert it to HTML table format using <table>, <tr>, <td> tags
        - For table merged cells, use colspan/rowspan attributes (e.g., <td colspan="2">)
        - If the image contains no meaningful text, return null for extractedText
        
        Example extracted text for an image with table: 
        You MUST output the table in HTML format, such as:
        <table>
            <thead>
                <tr>
                    <td colspan="3">價目表</td>
                </tr>
                <tr>
                    <td></td>
                    <td>be@me. 透明牙箍</td>
                    <td>be@me. PRO</td>
                </tr>
                <tr>
                    <td></td>
                    <td>牙齒更整齊</td>
                    <td>解決更複雜牙齒問題</td>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td>原價</td>
                    <td>$16,800</td>
                    <td>$28,620</td>
                </tr>
                <tr>
                    <td>網上預約折扣</td>
                    <td colspan="2">減$1,820</td>
                </tr>
                <tr>
                    <td>學生箍牙優惠</td>
                    <td colspan="2">再減 $500</td>
                </tr>
                <tr>
                    <td>優惠價</td>
                    <td>$14,480</td>
                    <td>$26,300</td>
                </tr>
            </tbody>
        </table>
        
        For images with both text and tables, combine them:
        Lorem ipsum is a text placeholder
        <table>...</table>
        More text after the table
        
        Expected output shape:
        {
            "extractedText": string | null
        }
    """.trimIndent()


    @Serializable
    private data class SingleImageTextExtractionResponse(
        val extractedText: String?
    )

    override suspend fun generate(input: MultiImageTextExtractionInput): MultiImageTextExtractionOutput {
        logger.debug(
            "Extracting text from {} images (processing each image individually in parallel)",
            input.images.size
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        val emptyTokenUsage = TokenUsageMetrics.empty(modelId)

        if (input.images.isEmpty()) {
            return MultiImageTextExtractionOutput(
                extractions = emptyList(),
                tokenUsage = emptyTokenUsage
            )
        }

        // Process each image individually, in parallel if multiple images
        return if (input.images.size == 1) {
            // Single image - process directly
            val (extraction, tokenUsage) = processSingleImage(input.images[0], 0)
            MultiImageTextExtractionOutput(
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
    private suspend fun processImagesInParallel(images: List<MultiImageTextExtractionInput.ImageItem>): MultiImageTextExtractionOutput =
        coroutineScope {
            logger.debug("Processing {} images in parallel", images.size)

            // Process all images in parallel
            val results = images.mapIndexed { index, image ->
                async {
                    processSingleImage(image, index)
                }
            }.awaitAll()

            // Combine results in order
            val allExtractions = results.map { it.first }
            val aggregatedTokenUsage =
                results.fold(TokenUsageMetrics.empty(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)) { acc, (_, tokenUsage) ->
                    TokenUsageMetrics(
                        modelName = acc.modelName,
                        promptTokens = acc.promptTokens + tokenUsage.promptTokens,
                        outputTokens = acc.outputTokens + tokenUsage.outputTokens,
                        totalTokens = acc.totalTokens + tokenUsage.totalTokens
                    )
                }

            MultiImageTextExtractionOutput(
                extractions = allExtractions,
                tokenUsage = aggregatedTokenUsage
            )
        }

    /**
     * Process a single image with a dedicated LLM call.
     * Returns a Pair of (extraction, tokenUsage) for aggregation.
     */
    private suspend fun processSingleImage(
        image: MultiImageTextExtractionInput.ImageItem,
        imageIndex: Int
    ): Pair<MultiImageTextExtractionOutput.TextExtraction, TokenUsageMetrics> {
        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Check if image is too large
        if (isImageTooLarge(image.bytes, image.mimeType)) {
            logger.error(
                "Image at position {} is too large (resolution exceeds {} pixels); returning empty string",
                imageIndex,
                MAX_PIXEL_COUNT
            )
            return MultiImageTextExtractionOutput.TextExtraction(extractedText = "") to tokenUsage
        }

        // Build content with single image
        val contentParts = listOf(
            Part.fromBytes(image.bytes, image.mimeType.value),
            Part.fromText("Extract all text from this image")
        )

        val response = retryLlmCall<SingleImageTextExtractionResponse> {
            val result = client.models.generateContent(
                modelId,
                listOf(Content.fromParts(*(contentParts.toTypedArray()))),
                GenerateContentConfig.builder()
                    .temperature(0.0F)
                    .responseSchema(outputSchema)
                    .responseMimeType("application/json")
                    .thinkingConfig(
                        ThinkingConfig.builder()
                            .thinkingBudget(0)
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

        // Transform HTML tables to markdown
        val extractedText = response.extractedText?.let { rawText ->
            if (rawText.isNotBlank()) {
                transformHTMLTablesToMarkdown(rawText).trim()
            } else {
                null
            }
        }

        if (extractedText != null && extractedText.isNotBlank()) {
            logger.debug("Text extracted from image at position {}: {}", imageIndex, extractedText)
        } else {
            logger.debug(
                "No text found in image at position {} ({} bytes)",
                imageIndex,
                image.bytes.size
            )
        }

        return MultiImageTextExtractionOutput.TextExtraction(extractedText = extractedText) to tokenUsage
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

    private fun transformHTMLTablesToMarkdown(text: String): String {
        // Replace only <table>...</table> blocks and leave the rest of the string untouched
        val pattern = Regex("(?is)<table\\b[\\s\\S]*?</table>")
        return pattern.replace(text) { match ->
            val tableHtml = match.value
            val doc = Jsoup.parseBodyFragment(tableHtml)
            val table = doc.selectFirst("table")
            if (table != null) {
                val mdBlock = buildString {
                    val caption = table.selectFirst("caption")?.text()?.trim()
                    if (!caption.isNullOrBlank()) {
                        appendLine(caption)
                    }
                    appendLine(tableToMarkdown(table))
                }.trimEnd()
                "\n$mdBlock\n"
            } else {
                tableHtml
            }
        }
    }

    private fun tableToMarkdown(table: Element): String {
        // Build a full grid handling both colspan and rowspan by duplicating values
        val grid: MutableList<MutableList<String>> = mutableListOf()
        var currentRowIndex = 0

        val tableRows = table.select("tr")
        if (tableRows.isEmpty()) return ""

        tableRows.forEach { tr ->
            // Ensure current row exists
            if (grid.size <= currentRowIndex) {
                grid.add(mutableListOf())
            }

            var colIndex = 0

            // Advance past any pre-filled cells from prior rowspans
            fun nextFreeCol(): Int {
                var idx = colIndex
                val row = grid[currentRowIndex]
                while (idx < row.size && row[idx].isNotEmpty()) {
                    idx++
                }
                return idx
            }

            tr.select("th, td").forEach { cell ->
                colIndex = nextFreeCol()
                val text = cell.text().trim()
                val colSpan = cell.attr("colspan").toIntOrNull() ?: 1
                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1

                val endColExclusive = colIndex + colSpan
                val endRowExclusive = currentRowIndex + rowSpan

                for (r in currentRowIndex until endRowExclusive) {
                    while (grid.size <= r) grid.add(mutableListOf())
                    val rowList = grid[r]
                    if (rowList.size < endColExclusive) {
                        repeat(endColExclusive - rowList.size) { rowList.add("") }
                    }
                    for (c in colIndex until endColExclusive) {
                        rowList[c] = text
                    }
                }

                colIndex = endColExclusive
            }

            currentRowIndex++
        }

        val numCols = grid.maxOfOrNull { it.size } ?: 0
        if (numCols == 0) return ""

        grid.forEach { r ->
            if (r.size < numCols) {
                repeat(numCols - r.size) { r.add("") }
            }
        }

        // Determine header row: prefer last thead row, else a row containing <th>, else first row
        val allTrs = table.select("tr").toList()
        val theadTrs = table.select("thead tr").toList()
        val headerIndex = when {
            theadTrs.isNotEmpty() -> allTrs.indexOf(theadTrs.last()).coerceAtLeast(0)
            allTrs.firstOrNull { it.select("th").isNotEmpty() } != null ->
                allTrs.indexOfFirst { it.select("th").isNotEmpty() }.coerceAtLeast(0)

            else -> 0
        }

        val header = grid.getOrNull(headerIndex) ?: MutableList(numCols) { "" }
        val dataRows = grid.filterIndexed { idx, _ -> idx != headerIndex }

        fun escapeMd(text: String): String =
            text.replace("|", "\\|")
                .replace('\n', ' ')
                .trim()

        fun renderLine(cells: List<String>): String =
            "| " + cells.map { escapeMd(it) }.joinToString(" | ") + " |"

        val separator = "| " + List(numCols) { "---" }.joinToString(" | ") + " |"

        val sb = StringBuilder()
        sb.appendLine(renderLine(header))
        sb.appendLine(separator)
        dataRows.forEach { row -> sb.appendLine(renderLine(row)) }

        return sb.toString().trimEnd()
    }
}


