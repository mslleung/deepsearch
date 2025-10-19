package io.deepsearch.application.services

import io.deepsearch.domain.agents.IStreamingAnswerAgent
import io.deepsearch.domain.agents.StreamingAnswerInput
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.SearchResult
import io.deepsearch.domain.models.entities.FinishReason
import kotlinx.coroutines.flow.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface IStreamingAnswerGenerationService {
    /**
     * Generate answer incrementally from markdown flow from background link traversal.
     * Returns as soon as a complete answer is obtained.
     * Updates session state to signal link traversal to stop after current wave.
     * 
     * @param sessionId Query session ID for coordination
     * @param searchQuery The search query
     * @param initialUrl The initial URL that was processed
     * @param initialMarkdown The markdown from the initial URL
     * @param resultsFlow Flow of additional URL processing results from link traversal (runs in background)
     * @return SearchResult with the answer (returns early when complete)
     */
    suspend fun generateAnswerStreaming(
        sessionId: String,
        searchQuery: SearchQuery,
        initialUrl: String,
        initialMarkdown: String,
        resultsFlow: Flow<UrlProcessingResult>
    ): SearchResult
}

class StreamingAnswerGenerationService(
    private val streamingAnswerAgent: IStreamingAnswerAgent,
    private val querySessionService: IQuerySessionService
) : IStreamingAnswerGenerationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val BATCH_SIZE = 20
    }

    override suspend fun generateAnswerStreaming(
        sessionId: String,
        searchQuery: SearchQuery,
        initialUrl: String,
        initialMarkdown: String,
        resultsFlow: Flow<UrlProcessingResult>
    ): SearchResult {
        logger.info("[{}] Starting incremental answer generation", sessionId)

        var currentAnswer: String? = null
        val allUrls = mutableListOf<String>()
        val allMarkdowns = mutableListOf<String>()
        val markdownBatch = mutableListOf<String>()

        // Start with initial result
        allUrls.add(initialUrl)
        allMarkdowns.add(initialMarkdown)
        if (initialMarkdown.isNotBlank()) {
            markdownBatch.add(initialMarkdown)
        }

        // Process the flow from background link traversal
        var answerCompleteEarly = false
        
        try {
            // Process all markdowns from the flow (flow is produced by background link traversal)
            resultsFlow.collect { result ->
                // Skip processing if answer is already complete
                if (answerCompleteEarly) {
                    return@collect
                }
                
                allUrls.add(result.url)
                allMarkdowns.add(result.markdown)

                // Still building answer - add to batch
                if (result.markdown.isNotBlank()) {
                    markdownBatch.add(result.markdown)
                }

                // Process batch when it reaches the batch size
                if (markdownBatch.size >= BATCH_SIZE) {
                    logger.debug("[{}] Processing batch of {} markdowns (total processed: {})", sessionId, markdownBatch.size, allMarkdowns.size)

                    val output = streamingAnswerAgent.generate(
                        StreamingAnswerInput(
                            query = searchQuery.query,
                            currentAnswer = currentAnswer,
                            markdownBatch = markdownBatch.toList()
                        )
                    )

                    currentAnswer = output.updatedAnswer
                    logger.debug("[{}] Answer updated: {} chars, complete: {}", sessionId, output.updatedAnswer.length, output.isComplete)

                    markdownBatch.clear()

                    // If answer is complete, signal link traversal to stop and set flag
                    if (output.isComplete) {
                        logger.info("[{}] Answer complete after {} pages, signaling link traversal to stop", sessionId, allUrls.size)
                        
                        val answer = currentAnswer ?: "No information found"
                        querySessionService.markAnswerComplete(sessionId, answer, allUrls.toList())
                        querySessionService.setFinishReason(sessionId, FinishReason.ANSWER_COMPLETE)
                        querySessionService.transitionToTrailingTraversal(sessionId)
                        
                        answerCompleteEarly = true
                    }
                }
            }

            // If answer completed early, return it now
            if (answerCompleteEarly) {
                return SearchResult(
                    originalQuery = searchQuery,
                    answer = currentAnswer ?: "No information found",
                    content = allMarkdowns.joinToString("\n\n---\n\n"),
                    sources = allUrls.toList()
                )
            }

            // Process any remaining markdowns in the final batch
            if (markdownBatch.isNotEmpty()) {
                logger.debug("[{}] Processing final batch of {} markdowns", sessionId, markdownBatch.size)

                val output = streamingAnswerAgent.generate(
                    StreamingAnswerInput(
                        query = searchQuery.query,
                        currentAnswer = currentAnswer,
                        markdownBatch = markdownBatch
                    )
                )

                currentAnswer = output.updatedAnswer
                logger.debug("[{}] Final answer: {} chars, complete: {}", sessionId, output.updatedAnswer.length, output.isComplete)
            }

            // Links exhausted - return whatever answer we have
            logger.info("[{}] Flow processing complete: {} pages total, answer complete: {}", 
                sessionId, allUrls.size, currentAnswer != null)

            val answer = currentAnswer ?: "No information found"
            querySessionService.markAnswerComplete(sessionId, answer, allUrls.toList())

            return SearchResult(
                originalQuery = searchQuery,
                answer = answer,
                content = allMarkdowns.joinToString("\n\n---\n\n"),
                sources = allUrls.toList()
            )
        } catch (e: Exception) {
            logger.error("[{}] Error during answer generation: {}", sessionId, e.message, e)
            throw e
        }
    }
}


