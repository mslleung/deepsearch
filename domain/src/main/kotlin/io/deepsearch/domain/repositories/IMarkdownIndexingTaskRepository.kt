package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.IndexingTaskType
import io.deepsearch.domain.models.entities.MarkdownIndexingTask

/**
 * Repository interface for MarkdownIndexingTask persistence.
 * 
 * Manages the task queue for asynchronous markdown indexing operations
 * (embeddings and knowledge graph extraction).
 */
interface IMarkdownIndexingTaskRepository {
    /**
     * Create a new indexing task.
     * @return The created task with assigned ID
     */
    suspend fun create(task: MarkdownIndexingTask): MarkdownIndexingTask

    /**
     * Batch create multiple indexing tasks.
     * More efficient than individual creates.
     * @return The created tasks with assigned IDs
     */
    suspend fun createBatch(tasks: List<MarkdownIndexingTask>): List<MarkdownIndexingTask>

    /**
     * Atomically claim the next pending task of the specified type.
     * 
     * Uses SELECT FOR UPDATE SKIP LOCKED to ensure only one worker
     * can claim each task, even with multiple concurrent workers.
     * 
     * @param taskType The type of task to claim
     * @return The claimed task (now IN_PROGRESS), or null if no pending tasks
     */
    suspend fun claimPendingTask(taskType: IndexingTaskType): MarkdownIndexingTask?

    /**
     * Mark a task as completed.
     * @param taskId The ID of the task to complete
     */
    suspend fun markCompleted(taskId: Long)

    /**
     * Mark a task as failed with an error message.
     * @param taskId The ID of the task
     * @param errorMessage Description of the failure
     */
    suspend fun markFailed(taskId: Long, errorMessage: String)

    /**
     * Retry a failed task or mark it as permanently failed.
     * 
     * If the task has remaining retry attempts, resets it to PENDING.
     * Otherwise, marks it as FAILED.
     * 
     * @param taskId The ID of the task
     * @param errorMessage Description of the current failure
     */
    suspend fun retryOrFail(taskId: Long, errorMessage: String)

    /**
     * Count pending tasks of a specific type.
     * @param taskType The type of task to count
     * @return Number of pending tasks
     */
    suspend fun countPending(taskType: IndexingTaskType): Long

    /**
     * Count all pending tasks (both types).
     * @return Number of pending tasks
     */
    suspend fun countAllPending(): Long

    /**
     * Find a task by ID.
     * @param id The task ID
     * @return The task, or null if not found
     */
    suspend fun findById(id: Long): MarkdownIndexingTask?

    /**
     * Delete completed tasks older than the specified age.
     * Used for periodic cleanup.
     * @param olderThanMs Age in milliseconds
     * @return Number of tasks deleted
     */
    suspend fun deleteCompletedOlderThan(olderThanMs: Long): Int

    /**
     * Reset stale in-progress tasks back to pending.
     * 
     * Tasks that have been in IN_PROGRESS state for too long
     * are assumed to have been abandoned (worker crash) and
     * should be retried.
     * 
     * @param staleTresholdMs Time in milliseconds after which a claimed task is considered stale
     * @return Number of tasks reset
     */
    suspend fun resetStaleTasks(staleTresholdMs: Long): Int
}

