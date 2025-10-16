package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.IPdfToMarkdownAgent
import io.deepsearch.domain.agents.PdfToMarkdownInput
import io.deepsearch.domain.agents.PdfToMarkdownOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * PDF to Markdown agent that converts PDF documents to markdown format.
 * Uses Gemini's native document processing capabilities.
 * 
 * Limitations per Gemini API:
 * - Maximum 1000 pages
 * - Maximum 20MB for inline data
 * - Each page = 258 tokens
 */
class PdfToMarkdownAgentAdkImpl : IPdfToMarkdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Markdown conversion of PDF document")
        .properties(
            mapOf(
                "markdown" to Schema.builder()
                    .type("STRING")
                    .description("PDF content converted to markdown format, preserving structure, headings, tables, and formatting")
                    .build()
            )
        )
        .required(listOf("markdown"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("pdfToMarkdownAgent")
        description("Convert PDF documents to markdown format")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
            You are a PDF to markdown conversion agent. Given a PDF document, convert it to markdown format.
            
            Instructions:
            - Preserve the document structure, including headings, paragraphs, lists, tables, and formatting
            - Convert headings to markdown heading syntax (# for h1, ## for h2, etc.)
            - Convert tables to markdown table format
            - Preserve emphasis (bold, italic) where recognizable
            - Extract all text content, including text from images if present
            - Maintain logical document flow and organization
            
            Expected output shape:
            {
                "markdown": string
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class PdfToMarkdownResponse(
        val markdown: String
    )

    override suspend fun generate(input: PdfToMarkdownInput): PdfToMarkdownOutput {
        logger.debug("Converting PDF to markdown ({} bytes)", input.pdfBytes.size)

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

        // Create content with PDF bytes
        val pdfPart = Part.fromBytes(input.pdfBytes, "application/pdf")
        val textPart = Part.fromText("Convert this PDF document to markdown format, preserving structure, headings, tables, and formatting.")
        
        val eventsFlow = runner.runAsync(
            session,
            Content.fromParts(pdfPart, textPart),
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

        val response = try {
            Json.decodeFromStringWithCodeBlocks<PdfToMarkdownResponse>(llmResponse)
        } catch (e: Exception) {
            logger.error("Failed to parse PDF markdown response: {}", e.message)
            // Return empty markdown on parsing failure
            PdfToMarkdownResponse(markdown = "")
        }

        logger.debug("Converted PDF to markdown: {} chars", response.markdown.length)
        return PdfToMarkdownOutput(markdown = response.markdown)
    }
}

