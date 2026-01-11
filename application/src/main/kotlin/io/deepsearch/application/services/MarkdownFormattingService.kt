package io.deepsearch.application.services

import io.deepsearch.domain.agents.IMarkdownFormattingAgent
import io.deepsearch.domain.agents.MarkdownFormattingInput
import io.deepsearch.domain.models.valueobjects.SessionId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Service for formatting raw extracted webpage content into well-structured markdown.
 */
interface IMarkdownFormattingService {
    /**
     * Formats raw extracted webpage content into a well-structured markdown document.
     *
     * @param rawText Raw text extracted from the webpage
     * @param url URL of the webpage
     * @param title Page title (may be null)
     * @param description Page meta description (may be null)
     * @param popupText Extracted popup text (may be null)
     * @param sessionId Session ID for token tracking
     * @return Formatted markdown document
     */
    suspend fun formatMarkdown(
        rawText: String,
        url: String,
        title: String?,
        description: String?,
        popupText: String?,
        sessionId: SessionId
    ): String
}

/**
 * Implementation of markdown formatting service.
 * 
 * Wraps the markdown formatting agent and handles token usage tracking.
 */
class MarkdownFormattingService(
    private val markdownFormattingAgent: IMarkdownFormattingAgent,
    private val tokenUsageService: ILlmTokenUsageService
) : IMarkdownFormattingService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun formatMarkdown(
        rawText: String,
        url: String,
        title: String?,
        description: String?,
        popupText: String?,
        sessionId: SessionId
    ): String {
        val result: String
        val duration = measureTimeMillis {
            val input = MarkdownFormattingInput(
                rawText = rawText,
                url = url,
                title = title,
                description = description,
                popupText = popupText
            )

            val output = markdownFormattingAgent.generate(input)

            // Record token usage
            tokenUsageService.recordTokenUsage(
                sessionId = sessionId,
                agentName = "MarkdownFormattingAgent",
                modelName = output.tokenUsage.modelName,
                promptTokens = output.tokenUsage.promptTokens,
                outputTokens = output.tokenUsage.outputTokens,
                totalTokens = output.tokenUsage.totalTokens
            )

            result = output.markdown
        }

        logger.debug(
            "Markdown formatting complete in {} ms: {} chars input -> {} chars output",
            duration, rawText.length, result.length
        )

        return result
    }
}
