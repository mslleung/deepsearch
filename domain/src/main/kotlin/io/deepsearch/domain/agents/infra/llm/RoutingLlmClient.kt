package io.deepsearch.domain.agents.infra.llm

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import io.deepsearch.domain.agents.infra.LlmBackend
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.flow.Flow

/**
 * [ILlmClient] that routes requests to the appropriate backend adapter based on the
 * model's [LlmBackend] as declared in [ModelIds].
 *
 * - Gemini models → [genAiClient] (Google GenAI SDK)
 * - MaaS open models (Gemma, etc.) → [openAiClient] (OpenAI-compatible endpoint)
 */
class RoutingLlmClient(
    private val genAiClient: GenAiLlmClient,
    private val openAiClient: OpenAiLlmClient,
) : ILlmClient {

    override suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): LlmResponse {
        return clientFor(model).generateContent(model, contents, config)
    }

    override fun generateContentStream(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): Flow<LlmResponse> {
        return clientFor(model).generateContentStream(model, contents, config)
    }

    private fun clientFor(model: String): ILlmClient {
        val backend = ModelIds.fromModelId(model)?.backend ?: LlmBackend.GENAI
        return when (backend) {
            LlmBackend.GENAI -> genAiClient
            LlmBackend.OPENAI_COMPATIBLE -> openAiClient
        }
    }
}
