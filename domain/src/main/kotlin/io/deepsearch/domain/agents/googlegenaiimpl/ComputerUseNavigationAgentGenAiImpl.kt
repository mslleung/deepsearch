package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.ComputerUse
import com.google.genai.types.Content
import com.google.genai.types.Environment
import com.google.genai.types.FunctionResponse
import com.google.genai.types.FunctionResponseBlob
import com.google.genai.types.FunctionResponsePart
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Tool
import io.deepsearch.domain.agents.ComputerUseResponse
import io.deepsearch.domain.agents.CuFunctionCall
import io.deepsearch.domain.agents.CuFunctionResult
import io.deepsearch.domain.agents.IComputerUseNavigationAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.withRateLimitRetry
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class ComputerUseNavigationAgentGenAiImpl(
    private val client: Client,
    private val dispatcherProvider: IDispatcherProvider
) : IComputerUseNavigationAgent {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private val history = mutableListOf<Content>()

    private val computerUseConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .tools(
            Tool.builder()
                .computerUse(
                    ComputerUse.builder()
                        .environment(Environment.Known.ENVIRONMENT_BROWSER)
                        .build()
                )
                .build()
        )
        .systemInstruction(
            Content.fromParts(
                Part.fromText(SYSTEM_INSTRUCTION)
            )
        )
        .build()

    override suspend fun startSession(
        query: String,
        screenshot: IBrowserPage.Screenshot,
        currentUrl: String
    ): ComputerUseResponse {
        history.clear()

        val userContent = Content.builder()
            .role("user")
            .parts(
                Part.fromText(buildInitialPrompt(query, currentUrl)),
                Part.fromBytes(screenshot.bytes, screenshot.mimeType.value)
            )
            .build()

        history.add(userContent)
        return callModel()
    }

    override suspend fun continueSession(
        functionResults: List<CuFunctionResult>
    ): ComputerUseResponse {
        val parts = functionResults.map { result ->
            val responseMap = mutableMapOf<String, Any>(
                "url" to result.currentUrl,
                "status" to if (result.error == null) "success" else "error"
            )
            if (result.error != null) {
                responseMap["message"] = result.error
            }

            val screenshotPart = FunctionResponsePart.builder()
                .inlineData(
                    FunctionResponseBlob.builder()
                        .mimeType(result.screenshot.mimeType.value)
                        .data(result.screenshot.bytes)
                        .build()
                )
                .build()

            val fnResponse = FunctionResponse.builder()
                .name(result.name)
                .id(result.callId)
                .response(responseMap)
                .parts(screenshotPart)
                .build()

            Part.builder().functionResponse(fnResponse).build()
        }

        history.add(
            Content.builder()
                .role("user")
                .parts(parts)
                .build()
        )

        return callModel()
    }

    override fun resetSession() {
        history.clear()
    }

    private suspend fun callModel(): ComputerUseResponse {
        val modelId = MODEL.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        logger.debug(
            "CU calling model: history size={}, parts per content={}",
            history.size,
            history.map { it.parts().orElse(emptyList()).size }
        )

        val response = try {
            withContext(dispatcherProvider.io) {
                withRateLimitRetry("ComputerUseNavigationAgent") {
                    client.models.generateContent(
                        modelId,
                        history,
                        computerUseConfig
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(
                "CU API error (history={} turns): {}",
                history.size, e.message
            )
            if (e.message?.contains("400") == true) {
                val lastContent = history.lastOrNull()
                val lastParts = lastContent?.parts()?.orElse(emptyList())
                logger.error(
                    "Last content role={}, parts count={}, part types={}",
                    lastContent?.role()?.orElse("?"),
                    lastParts?.size,
                    lastParts?.map { p ->
                        when {
                            p.functionResponse().isPresent -> "functionResponse(${p.functionResponse().get().name().orElse("?")})"
                            p.functionCall().isPresent -> "functionCall(${p.functionCall().get().name().orElse("?")})"
                            p.text().isPresent -> "text(${p.text().get().take(50)})"
                            p.inlineData().isPresent -> "inlineData(${p.inlineData().get().mimeType().orElse("?")})"
                            else -> "unknown"
                        }
                    }
                )
            }
            throw e
        }

        response.usageMetadata().ifPresent { metadata ->
            tokenUsage = TokenUsageMetrics(
                modelName = modelId,
                promptTokens = metadata.promptTokenCount().orElse(0),
                outputTokens = metadata.candidatesTokenCount().orElse(0),
                totalTokens = metadata.totalTokenCount().orElse(0)
            )
        }

        val candidate = response.candidates()
            .orElseThrow { RuntimeException("No candidates in CU response") }
            .firstOrNull()
            ?: throw RuntimeException("Empty candidates list in CU response")

        val modelContent = candidate.content()
            .orElseThrow { RuntimeException("No content in CU candidate") }

        history.add(modelContent)

        val parts = modelContent.parts().orElse(emptyList())

        val functionCalls = parts.mapNotNull { part ->
            part.functionCall().orElse(null)?.let { fc ->
                val args = fc.args().orElse(emptyMap())
                @Suppress("UNCHECKED_CAST")
                CuFunctionCall(
                    id = fc.id().orElse(""),
                    name = fc.name().orElse("unknown"),
                    args = args as Map<String, Any>,
                    intent = (args["intent"] as? String)
                )
            }
        }

        if (functionCalls.isNotEmpty()) {
            logger.info(
                "CU actions: {}",
                functionCalls.joinToString { "${it.name}(${it.intent ?: ""})" }
            )
            return ComputerUseResponse.Actions(functionCalls, tokenUsage)
        }

        val textParts = parts.mapNotNull { part ->
            part.text().orElse(null)
        }

        val rawText = textParts.joinToString("\n").trim()
        val answerText = stripThinkingPreamble(rawText)

        if (answerText.isNotEmpty()) {
            logger.info("CU finished with answer: {}...", answerText.take(100))
            return ComputerUseResponse.Answer(answerText, tokenUsage)
        }

        logger.warn("CU response had no function calls and no text, treating as give-up")
        return ComputerUseResponse.Answer("", tokenUsage)
    }

    companion object {
        val MODEL = ModelIds.GEMINI_3_5_FLASH

        private fun buildInitialPrompt(query: String, currentUrl: String): String = """
            You are on the page: $currentUrl
            
            Task: Navigate this webpage to find the answer to the following question.
            
            Question: $query
            
            Instructions:
            - Look at the screenshot and interact with the page to find the answer.
            - Click on accordions, tabs, or expandable sections if the answer might be hidden.
            - Scroll down if the content is below the viewport.
            - When you find the answer, respond with ONLY the factual answer text. Do NOT include explanations of how you found it.
            - If the information is not on this page after thorough exploration, say "INFORMATION_NOT_FOUND".
        """.trimIndent()

        private const val SYSTEM_INSTRUCTION = """You are a web research agent. Your task is to navigate a webpage using browser actions to find specific information requested by the user.

When you find the answer to the user's question, stop taking actions and respond with a clear, factual answer. Include specific numbers, prices, names, and details exactly as they appear on the page.

Do NOT navigate away from the current page. Stay on this page and explore it thoroughly.
Do NOT click links that would take you to a different page.
Do NOT interact with cookie consent banners, privacy prompts, or GDPR notices. Ignore them completely and focus on the page content behind them.
Focus on finding the specific information asked about."""

        private val THINKING_LINE_REGEX = Regex("""^\d+_thought\b.*$""", RegexOption.MULTILINE)

        /**
         * Gemini 3.5 Flash sometimes leaks internal reasoning prefixed with lines like
         * "10_thought\nThe price is clearly listed as ...".
         * Strip those lines so the caller gets only the factual answer.
         */
        fun stripThinkingPreamble(raw: String): String {
            return raw.replace(THINKING_LINE_REGEX, "")
                .trimStart('\n', ' ')
                .trim()
        }
    }
}
