package io.deepsearch.domain.agents.infra.llm

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import kotlinx.coroutines.flow.Flow

/**
 * Unified interface for LLM content generation, abstracting the underlying SDK.
 *
 * Accepts GenAI SDK input types ([Content], [GenerateContentConfig]) for pragmatic
 * compatibility with existing agent code. Returns SDK-independent [LlmResponse].
 *
 * Implementations route to the appropriate backend (e.g. Google GenAI, OpenAI-compatible).
 */
interface ILlmClient {

    /**
     * Generates content from the model in a single request-response cycle.
     *
     * @param model The model identifier (e.g. "gemini-3.5-flash", "gemma-4-26b-a4b-it-maas")
     * @param contents The conversation contents (user/model turns with text and/or image parts)
     * @param config Generation configuration (schema, temperature, system instruction, etc.)
     * @return The model's response including generated text and token usage
     */
    suspend fun generateContent(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): LlmResponse

    /**
     * Generates content from the model as a stream of partial responses.
     *
     * Each emitted [LlmResponse] contains the incremental text delta. Token usage
     * is typically only populated on the final chunk.
     *
     * @param model The model identifier
     * @param contents The conversation contents
     * @param config Generation configuration
     * @return A flow of incremental responses
     */
    fun generateContentStream(
        model: String,
        contents: List<Content>,
        config: GenerateContentConfig
    ): Flow<LlmResponse>
}
