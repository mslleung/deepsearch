package io.deepsearch.domain.agents.googlegenaiimpl

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
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
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
class PdfToMarkdownAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IPdfToMarkdownAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    companion object {
        private const val INLINE_DATA_MAX_SIZE_BYTES = 20 * 1024 * 1024 // 20MB
        private const val FILE_API_MAX_SIZE_BYTES = 50 * 1024 * 1024 // 50MB
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

    private val systemInstruction = """
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

        // Create content with PDF - use File API for large files, inline for small files
        val pdfPart = if (pdfSizeBytes > INLINE_DATA_MAX_SIZE_BYTES) {
            logger.debug("PDF size exceeds 20MB, using File API for upload")
            uploadPdfViaFileApi(input.pdfBytes)
        } else {
            logger.debug("PDF size is within 20MB limit, using inline data")
            Part.fromBytes(input.pdfBytes, "application/pdf")
        }
        
        val textPart = Part.fromText("Convert this PDF document to markdown format, preserving structure, headings, tables, and formatting.")

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        val response = try {
            retryLlmCall<PdfToMarkdownResponse> {
                val result = client.models.generateContent(
                    modelId,
                    listOf(Content.fromParts(pdfPart, textPart)),
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
        } catch (e: Exception) {
            logger.error("Failed to parse PDF markdown response after retries: {}", e.message)
            // Return empty markdown on parsing failure
            PdfToMarkdownResponse(markdown = "")
        }

        logger.debug("Converted PDF to markdown: {} chars", response.markdown.length)
        return PdfToMarkdownOutput(
            markdown = response.markdown,
            tokenUsage = tokenUsage
        )
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
        
        val uploadedFile = client.async.files.upload(pdfBytes, uploadConfig).await()
        
        val fileUri = uploadedFile.uri().orElseThrow {
            IllegalStateException("Uploaded file does not have a URI")
        }
        
        logger.debug("Successfully uploaded PDF to File API: {}", fileUri)
        
        return Part.fromUri(fileUri, "application/pdf")
    }
}


