package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Type of indexing task to perform on markdown content.
 */
enum class IndexingTaskType {
    /** Generate embeddings for hybrid search */
    EMBEDDING,
    /** Extract entities and relationships for knowledge graph */
    KNOWLEDGE_GRAPH
}

/**
 * Status of an indexing task.
 */
enum class IndexingTaskStatus {
    /** Task is waiting to be processed */
    PENDING,
    /** Task has been claimed by a worker */
    IN_PROGRESS,
    /** Task completed successfully */
    COMPLETED,
    /** Task failed after max retries */
    FAILED
}

/**
 * Represents a pending indexing task for markdown content.
 * 
 * Tasks are created when markdown is cached and processed asynchronously
 * by background workers. This replaces the fire-and-forget pattern with
 * a durable task queue that survives server restarts.
 * 
 * Each URL may have multiple tasks:
 * - EMBEDDING: Generate vector embeddings for hybrid search
 * - KNOWLEDGE_GRAPH: Extract entities and relationships
 */
@OptIn(ExperimentalTime::class)
data class MarkdownIndexingTask(
    val id: Long? = null,
    
    /** URL of the webpage being indexed */
    val url: String,
    
    /** Type of indexing task */
    val taskType: IndexingTaskType,
    
    /** Session ID for token usage tracking (stored as string for flexibility) */
    val sessionId: String,
    
    /** Current status of the task */
    val status: IndexingTaskStatus,
    
    /** Markdown content to process */
    val markdown: String,
    
    /** Number of processing attempts */
    val attempts: Int = 0,
    
    /** Maximum retry attempts before marking as failed */
    val maxAttempts: Int = 3,
    
    /** Error message if task failed */
    val errorMessage: String? = null,
    
    /** When the task was created */
    val createdAt: Instant,
    
    /** When the task was last updated */
    val updatedAt: Instant,
    
    /** When a worker claimed this task (null if not claimed) */
    val claimedAt: Instant? = null
) {
    companion object {
        /**
         * Create a new pending task.
         */
        fun createPending(
            url: String,
            taskType: IndexingTaskType,
            sessionId: String,
            markdown: String
        ): MarkdownIndexingTask {
            val now = Clock.System.now()
            return MarkdownIndexingTask(
                url = url,
                taskType = taskType,
                sessionId = sessionId,
                status = IndexingTaskStatus.PENDING,
                markdown = markdown,
                createdAt = now,
                updatedAt = now
            )
        }
    }
    
    /**
     * Create a copy marked as in-progress with incremented attempts.
     */
    fun claim(): MarkdownIndexingTask {
        val now = Clock.System.now()
        return copy(
            status = IndexingTaskStatus.IN_PROGRESS,
            attempts = attempts + 1,
            claimedAt = now,
            updatedAt = now
        )
    }
    
    /**
     * Create a copy marked as completed.
     */
    fun complete(): MarkdownIndexingTask {
        return copy(
            status = IndexingTaskStatus.COMPLETED,
            updatedAt = Clock.System.now()
        )
    }
    
    /**
     * Create a copy marked as failed with an error message.
     */
    fun fail(error: String): MarkdownIndexingTask {
        return copy(
            status = IndexingTaskStatus.FAILED,
            errorMessage = error,
            updatedAt = Clock.System.now()
        )
    }
    
    /**
     * Check if the task can be retried.
     */
    fun canRetry(): Boolean = attempts < maxAttempts
    
    /**
     * Create a copy reset to pending for retry, or marked as failed if max retries exceeded.
     */
    fun retryOrFail(error: String): MarkdownIndexingTask {
        return if (canRetry()) {
            copy(
                status = IndexingTaskStatus.PENDING,
                claimedAt = null,
                errorMessage = error,
                updatedAt = Clock.System.now()
            )
        } else {
            fail(error)
        }
    }
}

