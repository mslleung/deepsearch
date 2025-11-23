package io.deepsearch.domain.services

import io.deepsearch.domain.models.entities.LlmTokenUsage
import io.deepsearch.domain.repositories.ILlmTokenUsageRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.ExperimentalTime

/**
 * Service for recording LLM token usage with logging and persistence.
 */
interface ILlmTokenUsageService {
    /**
     * Record token usage for an LLM API call.
     * 
     * @param sessionId Optional query session ID for associating usage with a session
     * @param agentName Name of the agent making the call (for analysis)
     * @param modelName Name of the model used
     * @param promptTokens Number of tokens in the input prompt
     * @param outputTokens Number of tokens in the output
     * @param totalTokens Total tokens used
     */
    suspend fun recordTokenUsage(
        sessionId: String?,
        agentName: String,
        modelName: String,
        promptTokens: Int,
        outputTokens: Int,
        totalTokens: Int
    )
}

/**
 * Default implementation of LLM token usage service.
 * Logs token usage and persists to database when session ID is available.
 */
class LlmTokenUsageService(
    private val repository: ILlmTokenUsageRepository
) : ILlmTokenUsageService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @OptIn(ExperimentalTime::class)
    override suspend fun recordTokenUsage(
        sessionId: String?,
        agentName: String,
        modelName: String,
        promptTokens: Int,
        outputTokens: Int,
        totalTokens: Int
    ) {
        // Always log the token usage
        logger.info(
            "LLM Token Usage - Agent: {}, Model: {}, Session: {}, Prompt: {}, Output: {}, Total: {}",
            agentName,
            modelName,
            sessionId ?: "N/A",
            promptTokens,
            outputTokens,
            totalTokens
        )

        // Only persist to database if session ID is available
        if (sessionId != null) {
            try {
                val usage = LlmTokenUsage(
                    id = UUID.randomUUID().toString(),
                    querySessionId = sessionId,
                    agentName = agentName,
                    modelName = modelName,
                    promptTokens = promptTokens,
                    outputTokens = outputTokens,
                    totalTokens = totalTokens
                )
                repository.save(usage)
                logger.debug("Persisted token usage record for session: {}", sessionId)
            } catch (e: Exception) {
                logger.error("Failed to persist token usage for session {}: {}", sessionId, e.message, e)
                // Don't throw - token tracking should not break the main flow
            }
        } else {
            logger.debug("No session ID provided - token usage logged but not persisted")
        }
    }
}

