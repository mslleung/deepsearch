package io.deepsearch.domain.agents.infra.llm

/**
 * Unified response from an LLM call, independent of the underlying SDK.
 *
 * @property text The generated text content (null if the model produced no text)
 * @property inputTokens Number of prompt/input tokens consumed
 * @property outputTokens Number of completion/output tokens generated
 * @property totalTokens Total tokens (may differ from inputTokens + outputTokens when thinking tokens are present)
 * @property finishReason The reason the model stopped generating (e.g. "STOP", "MAX_TOKENS")
 */
data class LlmResponse(
    val text: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val finishReason: String?,
)
