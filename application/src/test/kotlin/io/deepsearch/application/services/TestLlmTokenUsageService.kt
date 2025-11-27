package io.deepsearch.application.services

import io.deepsearch.domain.models.valueobjects.SessionId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test implementation of ILlmTokenUsageService.
 * Accumulates token usage in memory and logs both individual and cumulative totals.
 * Does not persist to database.
 */
class TestLlmTokenUsageService : ILlmTokenUsageService {
    
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    
    // Thread-safe accumulators for total usage across all calls
    private val totalPromptTokens = AtomicInteger(0)
    private val totalOutputTokens = AtomicInteger(0)
    private val totalAllTokens = AtomicInteger(0)
    private val callCount = AtomicInteger(0)
    
    override suspend fun recordTokenUsage(
        sessionId: SessionId?,
        agentName: String,
        modelName: String,
        promptTokens: Int,
        outputTokens: Int,
        totalTokens: Int
    ) {
        // Accumulate totals
        val newTotalPrompt = totalPromptTokens.addAndGet(promptTokens)
        val newTotalOutput = totalOutputTokens.addAndGet(outputTokens)
        val newTotalAll = totalAllTokens.addAndGet(totalTokens)
        val newCallCount = callCount.incrementAndGet()
        
        // Log individual usage
        logger.info(
            "LLM Token Usage - Agent: {}, Model: {}, Session: {}, Prompt: {}, Output: {}, Total: {}",
            agentName,
            modelName,
            sessionId?.value ?: "N/A",
            promptTokens,
            outputTokens,
            totalTokens
        )
        
        // Log cumulative totals
        logger.info(
            "LLM Token Usage CUMULATIVE - Calls: {}, Total Prompt: {}, Total Output: {}, Total All: {}",
            newCallCount,
            newTotalPrompt,
            newTotalOutput,
            newTotalAll
        )
    }
}

