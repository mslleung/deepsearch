package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * This agent is designed to process multiple images efficiently by:
 * - Batching images into groups of up to 5 per LLM call
 * - Processing batches in parallel to maximize throughput
 * - Detecting and rejecting oversized images before LLM processing
 */
class MultiImageTextExtractionAgentAdkImpl : IMultiImageTextExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val BATCH_SIZE = 1
        private const val MAX_PIXEL_COUNT = 33_000_000L // ~33 million pixels (e.g., 6000×5500)
    }

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Extracted texts from multiple images")
        .properties(
            mapOf(
                "texts" to Schema.builder()
                    .type("ARRAY")
                    .description("Array of text extractions in order")
                    .items(
                        Schema.builder()
                            .type("OBJECT")
                            .description("Single image text extraction")
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
                    )
                    .build()
            )
        )
        .required(listOf("texts"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("multiImageTextExtractionAgent")
        description("Extract text from multiple images, preserving structure especially for tables")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .maxOutputTokens(8192)  // attempt to solve gemini no response issue
                .build()
        )
        instruction(
            """
            You are given multiple images in sequence that may contain text. Your task is to extract all visible text from each image.
            
            Instructions:
            - Extract all text present in each image, with reasonable line breaks
            - IMPORTANT: When you see data arranged in rows and columns (a table), you MUST convert it to HTML table format using <table>, <tr>, <td> tags
            - For table merged cells, use colspan/rowspan attributes (e.g., <td colspan="2">)
            - If an image contains no meaningful text, return null for that image
            - Return extracted texts in the same order as the images were provided
            
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
                "texts": [
                    {"extractedText": string | null}
                ]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class MultiImageTextExtractionResponse(
        val texts: List<TextResponse>
    )

    @Serializable
    private data class TextResponse(
        val extractedText: String?
    )

    override suspend fun generate(input: MultiImageTextExtractionInput): MultiImageTextExtractionOutput {
        logger.debug("Extracting text from {} images (will process in batches of {})", input.images.size, BATCH_SIZE)

        if (input.images.isEmpty()) {
            return MultiImageTextExtractionOutput(extractions = emptyList())
        }

        // Split images into batches of BATCH_SIZE and process in parallel
        return if (input.images.size <= BATCH_SIZE) {
            // Single batch - process directly
            processBatch(input.images)
        } else {
            // Multiple batches - process in parallel
            processInParallelBatches(input.images)
        }
    }

    /**
     * Process multiple batches of images in parallel.
     */
    private suspend fun processInParallelBatches(images: List<MultiImageTextExtractionInput.ImageItem>): MultiImageTextExtractionOutput =
        coroutineScope {
            val batches = images.chunked(BATCH_SIZE)
            logger.debug("Processing {} images in {} parallel batches", images.size, batches.size)

            // Process all batches in parallel
            val batchResults = batches.map { batch ->
                async {
                    processBatch(batch)
                }
            }.awaitAll()

            // Combine results from all batches in order
            val allExtractions = batchResults.flatMap { it.extractions }
            MultiImageTextExtractionOutput(extractions = allExtractions)
        }

    /**
     * Process a single batch of images (up to BATCH_SIZE).
     */
    private suspend fun processBatch(images: List<MultiImageTextExtractionInput.ImageItem>): MultiImageTextExtractionOutput {
        logger.debug("Processing batch of {} images", images.size)

        // Pre-filter oversized images to avoid LLM processing
        // Track which positions in the batch are oversized
        val oversizedPositions = mutableSetOf<Int>()
        val imagesToProcess = images.filterIndexed { index, image ->
            if (isImageTooLarge(image.bytes, image.mimeType)) {
                logger.error(
                    "Image at batch position {} is too large (resolution exceeds {} pixels); returning empty string",
                    index,
                    MAX_PIXEL_COUNT
                )
                oversizedPositions.add(index)
                false
            } else {
                true
            }
        }

        // If all images in batch are oversized, return early with empty strings
        if (imagesToProcess.isEmpty()) {
            return MultiImageTextExtractionOutput(
                extractions = images.map {
                    MultiImageTextExtractionOutput.TextExtraction(extractedText = "")
                }
            )
        }

        // Build content with all images
        val contentParts = mutableListOf<Part>()

        // Add each image as an image part with position label
        imagesToProcess.forEachIndexed { index, image ->
            contentParts.add(Part.fromText("Image ${index + 1}:"))
            contentParts.add(Part.fromBytes(image.bytes, image.mimeType.value))
        }

        // Add instruction text
        contentParts.add(Part.fromText("Extract text from the above ${imagesToProcess.size} images in order"))

        val response = retryLlmCall<MultiImageTextExtractionResponse> {
            val session = runner
                .sessionService()
                .createSession(
                    this::class.simpleName,
                    this::class.simpleName,
                    null,
                    null
                )
                .await()

            var llmResponse = ""

            val eventsFlow = runner.runAsync(
                session,
                Content.fromParts(*(contentParts.toTypedArray())),
                RunConfig.builder().apply {
                    setStreamingMode(RunConfig.StreamingMode.NONE)
                    setMaxLlmCalls(1)
                }.build()
            ).asFlow()

            eventsFlow.collect { event ->
                if (event.finalResponse() && event.content().isPresent) {
                    val content = event.content().get()
                    if (content.parts().isPresent
                        && !content.parts().get().isEmpty()
                        && content.parts().get()[0].text().isPresent
                    ) {
                        if (!event.partial().orElse(false)) {
                            llmResponse = content.parts().get()[0].text().get()
                        }
                    }
                }
            }

            llmResponse
        }

        // Transform HTML tables to markdown for each extracted text
        val transformedTexts = response.texts.map { textResponse ->
            textResponse.extractedText?.let { rawText ->
                if (rawText.isNotBlank()) {
                    transformHTMLTablesToMarkdown(rawText).trim()
                } else {
                    null
                }
            }
        }

        // Reconstruct full batch output list, inserting LLM results at non-oversized positions
        val llmResultsIterator = transformedTexts.iterator()
        val extractions = images.indices.map { position ->
            val extractedText = if (oversizedPositions.contains(position)) {
                ""
            } else {
                llmResultsIterator.next()
            }

            if (extractedText != null && extractedText.isNotBlank()) {
                logger.debug("Text extracted from image at batch position {}: {}", position, extractedText)
            } else {
                logger.debug(
                    "No text found in image at batch position {} ({} bytes)",
                    position,
                    images[position].bytes.size
                )
            }

            MultiImageTextExtractionOutput.TextExtraction(extractedText = extractedText)
        }

        return MultiImageTextExtractionOutput(extractions = extractions)
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

