package io.deepsearch.application.services.batch

import io.deepsearch.application.services.ILlmTokenUsageService
import io.deepsearch.domain.models.valueobjects.BatchPeriodicIndexSessionId
import io.deepsearch.domain.services.BatchResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Helper class for recording batch token usage.
 * 
 * Aggregates token usage from batch results and records them using the LLM token usage service.
 * This enables cost tracking for batch periodic index jobs.
 */
class BatchTokenUsageRecorder(
    private val tokenUsageService: ILlmTokenUsageService
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Record aggregated token usage from batch results.
     * 
     * @param jobId The batch job ID
     * @param agentName Name of the batch stage/agent (e.g., "ContentLlmBatch", "TableInterpretationBatch")
     * @param modelName The model used for the batch
     * @param results The batch results containing token usage
     */
    suspend fun recordBatchTokenUsage(
        jobId: Long,
        agentName: String,
        modelName: String,
        results: List<BatchResult>
    ) {
        if (results.isEmpty()) return

        val sessionId = BatchPeriodicIndexSessionId(jobId)
        
        val totalPromptTokens = results.sumOf { it.promptTokens }
        val totalOutputTokens = results.sumOf { it.outputTokens }
        val totalTokens = results.sumOf { it.totalTokens }

        // Only record if there's actual token usage
        if (totalPromptTokens == 0 && totalOutputTokens == 0 && totalTokens == 0) {
            logger.debug("[{}] No token usage to record for {}", jobId, agentName)
            return
        }

        logger.info(
            "[{}] Recording batch token usage for {}: prompt={}, output={}, total={}",
            jobId, agentName, totalPromptTokens, totalOutputTokens, totalTokens
        )

        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = agentName,
            modelName = modelName,
            promptTokens = totalPromptTokens,
            outputTokens = totalOutputTokens,
            totalTokens = totalTokens
        )
    }
}
