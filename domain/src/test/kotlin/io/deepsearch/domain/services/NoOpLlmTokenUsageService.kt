package io.deepsearch.domain.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * No-op implementation of ILlmTokenUsageService for testing.
 * Logs token usage at debug level but doesn't persist to database.
 */
class NoOpLlmTokenUsageService : ILlmTokenUsageService {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun recordTokenUsage(
        sessionId: String?,
        agentName: String,
        modelName: String,
        promptTokens: Int,
        outputTokens: Int,
        totalTokens: Int
    ) {
        logger.debug(
            "Token usage: agent={}, model={}, session={}, prompt={}, output={}, total={}",
            agentName, modelName, sessionId ?: "null", promptTokens, outputTokens, totalTokens
        )
    }
}

