package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.SessionId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a single LLM API call's token usage for cost tracking and analysis.
 * 
 * This entity captures all relevant token usage metrics from LLM calls made through
 * the Google Gen AI SDK, including both text generation and embedding calls.
 */
@OptIn(ExperimentalTime::class)
data class LlmTokenUsage(
    val id: String,
    val sessionId: SessionId?,
    val agentName: String,
    val modelName: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val createdAt: Instant = Clock.System.now()
)

