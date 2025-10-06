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
            - Extract all text present in the image accurately
            - If the image contains a table, preserve the table structure using markdown table format
            - If the image contains no meaningful text, return null
            - Maintain the reading order and layout of the text as much as possible
            - For tables, use markdown table syntax with pipes (|) and hyphens (-) to create rows and columns
            - Ensure table headers are properly aligned with table data
            - For merged cells, please duplicate the cell value to all corresponding cells in the markdown table.
            
            Examples of table format:
            | Header 1 | Header 2 | Header 3 |
            |----------|----------|----------|
            | Cell 1   | Cell 2   | Cell 3   |
            | Cell 4   | Cell 5   | Cell 6   |
            
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
            logger.debug("Text extracted from image: {} characters", response.extractedText.length)
            return ImageTextExtractionOutput(extractedText = "[ ${response.extractedText.trim()} ]")
        } else {
            logger.debug("No text found in image ({} bytes)", input.bytes.size)
            return ImageTextExtractionOutput(extractedText = null)
        }
    }
}
