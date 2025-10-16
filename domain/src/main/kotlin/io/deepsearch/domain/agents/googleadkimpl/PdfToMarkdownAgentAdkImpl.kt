package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.UploadFileConfig
import io.deepsearch.domain.agents.IPdfToMarkdownAgent
import io.deepsearch.domain.agents.PdfToMarkdownInput
import io.deepsearch.domain.agents.PdfToMarkdownOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import kotlinx.coroutines.future.await
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
 * - Maximum 20MB for inline data (uses File API for larger files)
 * - Maximum 50MB when using File API
 * - Each page = 258 tokens
 */
class PdfToMarkdownAgentAdkImpl : IPdfToMarkdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        private const val INLINE_DATA_MAX_SIZE_BYTES = 20 * 1024 * 1024 // 20MB
        private const val FILE_API_MAX_SIZE_BYTES = 50 * 1024 * 1024 // 50MB
    }
    
    // Lazy-initialized client for file uploads when needed
    private val genaiClient: Client by lazy {
        // will resolve apikey using environment variable
        Client.builder().build()
    }

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
        val pdfSizeBytes = input.pdfBytes.size
        logger.debug("Converting PDF to markdown ({} bytes)", pdfSizeBytes)

        // Validate file size
        require(pdfSizeBytes <= FILE_API_MAX_SIZE_BYTES) {
            "PDF size ($pdfSizeBytes bytes) exceeds maximum allowed size ($FILE_API_MAX_SIZE_BYTES bytes / 50MB)"
        }

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

        // Create content with PDF - use File API for large files, inline for small files
        val pdfPart = if (pdfSizeBytes > INLINE_DATA_MAX_SIZE_BYTES) {
            logger.debug("PDF size exceeds 20MB, using File API for upload")
            uploadPdfViaFileApi(input.pdfBytes)
        } else {
            logger.debug("PDF size is within 20MB limit, using inline data")
            Part.fromBytes(input.pdfBytes, "application/pdf")
        }
        
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
    
    /**
     * Uploads a PDF via the File API and returns a Part referencing the uploaded file.
     * Used for PDFs larger than 20MB (up to 50MB).
     */
    private suspend fun uploadPdfViaFileApi(pdfBytes: ByteArray): Part {
        logger.debug("Uploading PDF to File API ({} bytes)", pdfBytes.size)
        
        val uploadConfig = UploadFileConfig.builder()
            .mimeType("application/pdf")
            .displayName("pdf-to-markdown-${System.currentTimeMillis()}")
            .build()
        
        val uploadedFile = genaiClient.async.files.upload(pdfBytes, uploadConfig).await()
        
        val fileUri = uploadedFile.uri().orElseThrow {
            IllegalStateException("Uploaded file does not have a URI")
        }
        
        logger.debug("Successfully uploaded PDF to File API: {}", fileUri)
        
        return Part.fromUri(fileUri, "application/pdf")
    }
}

