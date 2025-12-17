package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId

/**
 * Repository interface for BatchPeriodicIndexJob persistence.
 */
interface IBatchPeriodicIndexJobRepository {
    /**
     * Create a new batch periodic index job.
     * @return The created job with assigned ID
     */
    suspend fun create(job: BatchPeriodicIndexJob): BatchPeriodicIndexJob

    /**
     * Update an existing batch periodic index job.
     * Uses optimistic locking via version field.
     */
    suspend fun update(job: BatchPeriodicIndexJob)

    /**
     * Find a batch job by ID.
     */
    suspend fun findById(id: Long): BatchPeriodicIndexJob?

    /**
     * Find all batch jobs by user ID.
     */
    suspend fun findByUserId(userId: UserId): List<BatchPeriodicIndexJob>

    /**
     * Find all batch jobs in a given state.
     * Used for resumption after server restart.
     */
    suspend fun findByState(state: BatchPeriodicIndexJobState): List<BatchPeriodicIndexJob>

    /**
     * Find all batch jobs in non-terminal states.
     * Used for resumption after server restart.
     */
    suspend fun findActiveJobs(): List<BatchPeriodicIndexJob>

    /**
     * List all batch jobs, optionally filtered by state.
     */
    suspend fun listAll(state: BatchPeriodicIndexJobState? = null): List<BatchPeriodicIndexJob>

    /**
     * Find the last completed batch job for a user and base URL.
     * Used to carry over URLs from previous jobs.
     */
    suspend fun findLastCompletedByUserIdAndBaseUrl(userId: UserId, baseUrl: String): BatchPeriodicIndexJob?

    /**
     * Delete a batch job by ID.
     */
    suspend fun delete(id: Long)
}

