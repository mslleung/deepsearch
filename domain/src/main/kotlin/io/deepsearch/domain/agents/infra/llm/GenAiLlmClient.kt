package io.deepsearch.domain.agents.infra.llm

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [ILlmClient] adapter that delegates to the Google GenAI SDK ([Client]).
 * Used for Gemini models that support the native :generateContent endpoint.
 */
class GenAiLlmClient(private val client: Client) : ILlmClient {

    override suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): LlmResponse {
        val result = client.models.generateContent(model, contents, config)
        result.checkFinishReason()

        val usage = result.usageMetadata().orElse(null)
        val inputTokens = usage?.promptTokenCount()?.orElse(0)?.toLong() ?: 0L
        val outputTokens = usage?.candidatesTokenCount()?.orElse(0)?.toLong() ?: 0L
        val totalTokens = usage?.totalTokenCount()?.orElse(0)?.toLong() ?: 0L

        val finishReason = result.candidates().orElse(null)
            ?.firstOrNull()
            ?.finishReason()?.orElse(null)
            ?.toString()

        return LlmResponse(
            text = result.text(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            finishReason = finishReason,
        )
    }

    override fun generateContentStream(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): Flow<LlmResponse> = flow {
        val stream = client.models.generateContentStream(model, contents, config)
        for (chunk in stream) {
            val usage = chunk.usageMetadata().orElse(null)

            emit(
                LlmResponse(
                    text = chunk.text(),
                    inputTokens = usage?.promptTokenCount()?.orElse(0)?.toLong() ?: 0L,
                    outputTokens = usage?.candidatesTokenCount()?.orElse(0)?.toLong() ?: 0L,
                    totalTokens = usage?.totalTokenCount()?.orElse(0)?.toLong() ?: 0L,
                    finishReason = null,
                )
            )
        }
    }
}
