package io.deepsearch.domain.models.valueobjects

/**
 * Token usage metrics returned by LLM agents.
 * Agents return these metrics; callers decide how to record them.
 */
data class TokenUsageMetrics(
    val modelName: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int
) {
    /**
     * Combines token usage from multiple LLM calls.
     * Uses the model name from this instance.
     */
    operator fun plus(other: TokenUsageMetrics): TokenUsageMetrics {
        return TokenUsageMetrics(
            modelName = this.modelName,
            promptTokens = this.promptTokens + other.promptTokens,
            outputTokens = this.outputTokens + other.outputTokens,
            totalTokens = this.totalTokens + other.totalTokens
        )
    }

    companion object {
        /**
         * Empty metrics for operations that don't use tokens (e.g., cached results, fallbacks).
         */
        fun empty(modelName: String = "none"): TokenUsageMetrics {
            return TokenUsageMetrics(
                modelName = modelName,
                promptTokens = 0,
                outputTokens = 0,
                totalTokens = 0
            )
        }
    }
}

