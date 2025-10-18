package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.QuerySession

/**
 * Repository for persisting and retrieving QuerySession entities.
 */
interface IQuerySessionRepository {
    /**
     * Save a new query session.
     */
    suspend fun save(session: QuerySession): QuerySession
    
    /**
     * Find a query session by ID.
     */
    suspend fun findById(id: String): QuerySession?
    
    /**
     * Update an existing query session.
     */
    suspend fun update(session: QuerySession): QuerySession
}

