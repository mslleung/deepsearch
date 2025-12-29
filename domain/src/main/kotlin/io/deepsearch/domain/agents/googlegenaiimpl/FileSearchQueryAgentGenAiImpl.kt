package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.FileSearch
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Tool
import io.deepsearch.domain.agents.FileSearchQueryInput
import io.deepsearch.domain.agents.FileSearchQueryOutput
import io.deepsearch.domain.agents.IFileSearchQueryAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.FileSearchChunk
import io.deepsearch.domain.models.valueobjects.GeminiFileMetadata
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Agent that queries a Gemini File Search store using the SDK's FileSearch tool.
 *
 * This agent uses the native FileSearch tool to perform RAG over previously uploaded
 * documents in a file search store.
 *
 * Reference: https://ai.google.dev/gemini-api/docs/file-search
 */
class FileSearchQueryAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IFileSearchQueryAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val systemInstruction = """
        Answer using only information from the file search results. Include relevant quotes.
        If the information is not found in the search results, say you cannot find relevant information.
    """.trimIndent()

    override suspend fun generate(input: FileSearchQueryInput): FileSearchQueryOutput {
        val (storeName, query, maxAgeMs) = input
        logger.debug("File search query on store '{}': '{}' (maxAgeMs: {})", storeName, query, maxAgeMs)

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        // Build FileSearch with optional metadata filter for age
        val fileSearchBuilder = FileSearch.builder()
            .fileSearchStoreNames(listOf(storeName))
            .topK(15)

        // Apply metadata filter if maxAgeMs is specified
        // Uses AIP-160 filter syntax: https://google.aip.dev/160
        if (maxAgeMs != null) {
            val threshold = System.currentTimeMillis() - maxAgeMs
            val filter = "${GeminiFileMetadata.KEY_UPLOADED_AT} >= $threshold"
            fileSearchBuilder.metadataFilter(filter)
            logger.debug("Applying metadata filter: {}", filter)
        }

        val fileSearchTool = Tool.builder()
            .fileSearch(fileSearchBuilder.build())
            .build()

        val response = withContext(dispatcherProvider.io) {
            withRateLimitRetry(this@FileSearchQueryAgentGenAiImpl::class.simpleName!!) {
                client.models.generateContent(
                    modelId,
                    query,
                    GenerateContentConfig.builder()
                        .temperature(0F)
                        .tools(listOf(fileSearchTool))
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )
            }
        }

        // Extract token usage
        response.usageMetadata().ifPresent { metadata ->
            tokenUsage = TokenUsageMetrics(
                modelName = modelId,
                promptTokens = metadata.promptTokenCount().orElse(0),
                outputTokens = metadata.candidatesTokenCount().orElse(0),
                totalTokens = metadata.totalTokenCount().orElse(0)
            )
        }

        val contentText = response.text() ?: ""
        val chunks = parseResponseToChunks(contentText)

        logger.debug("File search returned {} chunks", chunks.size)

        return FileSearchQueryOutput(
            chunks = chunks,
            tokenUsage = tokenUsage
        )
    }

    private fun parseResponseToChunks(content: String): List<FileSearchChunk> {
        if (content.isBlank()) {
            return emptyList()
        }

        // The response from file search is typically a synthesized answer
        // We wrap it as a single chunk since the SDK handles the RAG internally
        return listOf(
            FileSearchChunk(
                content = content,
                sourceUrl = "",
                fileName = "File Search Result",
                relevanceScore = null
            )
        )
    }
}

