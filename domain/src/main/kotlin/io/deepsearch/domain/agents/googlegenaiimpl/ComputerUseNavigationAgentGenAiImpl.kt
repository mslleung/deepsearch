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
        .temperature(1.0f)
        .maxOutputTokens(8192)
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

        val compressedHistory = compressHistoryImages(history.toList())
        history.clear()
        history.addAll(compressedHistory)

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

        val sanitizedContent = sanitizeCoordinates(modelContent)
        history.add(sanitizedContent)

        val parts = sanitizedContent.parts().orElse(emptyList())

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

    /**
     * Compress older screenshots to JPEG at reduced quality instead of removing them.
     * Keeps the [KEEP_RECENT_SCREENSHOTS] most recent screenshots at full PNG quality;
     * converts older ones to JPEG 25% quality to reduce token cost while retaining visual context.
     */
    private fun compressHistoryImages(history: List<Content>): List<Content> {
        if (history.size <= 1) return history.toList()

        val screenshotIndices = mutableListOf<Pair<Int, Int>>()
        history.forEachIndexed { contentIdx, content ->
            content.parts().orElse(emptyList()).forEachIndexed { partIdx, part ->
                val hasImage = part.inlineData().isPresent ||
                    (part.functionResponse().isPresent &&
                        part.functionResponse().get().parts().orElse(emptyList()).any { fp ->
                            fp.inlineData().isPresent
                        })
                if (hasImage) {
                    screenshotIndices.add(contentIdx to partIdx)
                }
            }
        }

        if (screenshotIndices.size <= KEEP_RECENT_SCREENSHOTS) return history.toList()

        val indicesToCompress = screenshotIndices.dropLast(KEEP_RECENT_SCREENSHOTS).toSet()

        return history.mapIndexed { contentIdx, content ->
            val oldParts = content.parts().orElse(emptyList())
            val hasCompressiblePart = oldParts.indices.any { partIdx ->
                (contentIdx to partIdx) in indicesToCompress
            }

            if (!hasCompressiblePart) {
                content
            } else {
                val role = content.role().orElse("user")
                val newParts = oldParts.mapIndexedNotNull { partIdx, part ->
                    if ((contentIdx to partIdx) in indicesToCompress) {
                        when {
                            part.inlineData().isPresent -> {
                                val data = part.inlineData().get()
                                val compressed = compressToJpeg(
                                    data.data().orElse(ByteArray(0)),
                                    data.mimeType().orElse("image/png")
                                )
                                if (compressed != null) {
                                    Part.fromBytes(compressed, "image/jpeg")
                                } else {
                                    null
                                }
                            }
                            part.functionResponse().isPresent -> {
                                val oldFn = part.functionResponse().get()
                                val oldFnParts = oldFn.parts().orElse(emptyList())
                                val newFnParts = oldFnParts.mapNotNull { fp ->
                                    if (fp.inlineData().isPresent) {
                                        val data = fp.inlineData().get()
                                        val compressed = compressToJpeg(
                                            data.data().orElse(ByteArray(0)),
                                            data.mimeType().orElse("image/png")
                                        )
                                        if (compressed != null) {
                                            FunctionResponsePart.builder()
                                                .inlineData(
                                                    FunctionResponseBlob.builder()
                                                        .mimeType("image/jpeg")
                                                        .data(compressed)
                                                        .build()
                                                )
                                                .build()
                                        } else {
                                            null
                                        }
                                    } else {
                                        fp
                                    }
                                }

                                val newFnBuilder = FunctionResponse.builder()
                                    .name(oldFn.name().orElse(""))
                                    .id(oldFn.id().orElse(""))
                                    .response(oldFn.response().orElse(emptyMap()))
                                if (newFnParts.isNotEmpty()) {
                                    newFnBuilder.parts(newFnParts)
                                }
                                Part.builder().functionResponse(newFnBuilder.build()).build()
                            }
                            else -> part
                        }
                    } else {
                        part
                    }
                }
                Content.builder()
                    .role(role)
                    .parts(newParts)
                    .build()
            }
        }
    }

    private fun compressToJpeg(imageBytes: ByteArray, @Suppress("UNUSED_PARAMETER") mimeType: String): ByteArray? {
        if (imageBytes.isEmpty()) return null
        return try {
            val inputStream = java.io.ByteArrayInputStream(imageBytes)
            val bufferedImage = javax.imageio.ImageIO.read(inputStream) ?: return null
            val outputStream = java.io.ByteArrayOutputStream()
            val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                compressionQuality = JPEG_COMPRESSION_QUALITY
            }
            writer.output = javax.imageio.stream.MemoryCacheImageOutputStream(outputStream)
            writer.write(null, javax.imageio.IIOImage(bufferedImage, null, null), param)
            writer.dispose()
            outputStream.toByteArray()
        } catch (e: Exception) {
            logger.warn("Failed to compress screenshot to JPEG: {}", e.message)
            null
        }
    }

    /**
     * Clamp any out-of-range coordinates (>999) in function call args to 999.
     * Prevents cascading errors from polluting conversation history.
     */
    private fun sanitizeCoordinates(content: Content): Content {
        val parts = content.parts().orElse(emptyList())
        var modified = false

        val newParts = parts.map { part ->
            val fc = part.functionCall().orElse(null)
            if (fc != null) {
                val args = fc.args().orElse(emptyMap())
                val xVal = (args["x"] as? Number)?.toDouble()
                val yVal = (args["y"] as? Number)?.toDouble()

                if ((xVal != null && xVal > 999) || (yVal != null && yVal > 999)) {
                    modified = true
                    val newArgs = args.toMutableMap()
                    if (xVal != null && xVal > 999) newArgs["x"] = 999
                    if (yVal != null && yVal > 999) newArgs["y"] = 999
                    Part.fromFunctionCall(fc.name().orElse(""), newArgs)
                } else {
                    part
                }
            } else {
                part
            }
        }

        return if (modified) {
            Content.builder()
                .role(content.role().orElse("model"))
                .parts(newParts)
                .build()
        } else {
            content
        }
    }

    companion object {
        val MODEL = ModelIds.GEMINI_3_5_FLASH

        private const val KEEP_RECENT_SCREENSHOTS = 2
        private const val JPEG_COMPRESSION_QUALITY = 0.25f

        private fun buildInitialPrompt(query: String, currentUrl: String): String = """
            Starting page: $currentUrl
            
            Find the answer to: $query
            
            When you find the answer, respond with ONLY the factual answer. Do NOT include explanations.
            If the information cannot be found after thorough exploration, say "INFORMATION_NOT_FOUND".
        """.trimIndent()

        private const val SYSTEM_INSTRUCTION =
            "You are a browser agent whose job is to find specific information on a website. " +
            "Navigate the site using browser actions to locate the answer to the user's question. " +
            "When you find the answer, stop taking actions and respond with a clear, factual answer including specific numbers, prices, names, and details exactly as they appear on the page. " +
            "Stay on the target website. Ignore cookie banners and privacy prompts."

        private val THINKING_LINE_REGEX = Regex("""^\d+_thought\b.*$""", RegexOption.MULTILINE)

        fun stripThinkingPreamble(raw: String): String {
            return raw.replace(THINKING_LINE_REGEX, "")
                .trimStart('\n', ' ')
                .trim()
        }
    }
}
