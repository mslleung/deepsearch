package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IImageTextExtractionAgent
import io.deepsearch.domain.agents.ImageTextExtractionInput
import io.deepsearch.domain.agents.ImageTextExtractionOutput
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Multimodal image text extraction agent.
 *
 * Given an image that may contain text (including tables), extract the text
 * and preserve structure (especially for tables).
 */
class ImageTextExtractionAgentAdkImpl : IImageTextExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Extracted text from an image")
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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("imageTextExtractionAgent")
        description("Extract text from an image, preserving structure especially for tables")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.0F)
                .build()
        )
        instruction(
            """
            You are given an image that may contain text. Your task is to extract all visible text from the image.
            
            Instructions:
            - Extract all text present in the image
            - For each table inside the image, convert to a HTML table
            - For table merged cells, please unmerge them by duplicating the cell value to all corresponding cells.
            - If the image contains no meaningful text, return null
            
            Example output shape for an image containing a mix of text and tables:
            Lorem ipsum is a text placeholder
            <table>
                <thead>
                    <tr>
                        <td colspan="3">價目表</td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td colspan="1">be@me. 透明牙箍</td>
                        <td colspan="1">be@me. PRO</td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td colspan="1">牙齒更整齊</td>
                        <td colspan="1">解決更複雜牙齒問題</td>
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
            Lorem ipsum is a text placeholder
            
            Expected output shape:
            {
                "extractedText": string | null
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class ImageTextExtractionResponse(
        val extractedText: String?
    )

    override suspend fun generate(input: ImageTextExtractionInput): ImageTextExtractionOutput {
        logger.debug("Extracting text from image ({} bytes, {})", input.bytes.size, input.mimeType.value)

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
            Content.fromParts(
                Part.fromBytes(input.bytes, input.mimeType.value),
            ),
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

        val response = Json.decodeFromString<ImageTextExtractionResponse>(llmResponse)

        if (!response.extractedText.isNullOrBlank()) {
            val transformed = transformHTMLTablesToMarkdown(response.extractedText)
            logger.debug("Text extracted from image: {} characters", transformed.length)
            return ImageTextExtractionOutput(extractedText = transformed.trim())
        } else {
            logger.debug("No text found in image ({} bytes)", input.bytes.size)
            return ImageTextExtractionOutput(extractedText = null)
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

    // Note: We intentionally do not serialize non-table HTML; we only replace table blocks inline
}
