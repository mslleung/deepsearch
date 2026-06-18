package io.deepsearch.domain.repositories

import io.deepsearch.domain.models.entities.AgenticNavIteration
import io.deepsearch.domain.models.entities.ScreenshotRecord
import io.deepsearch.domain.models.valueobjects.AgenticNavIterationId
import io.deepsearch.domain.models.valueobjects.SessionId

/**
 * Repository port for persisting agentic navigation iteration metadata.
 *
 * Each iteration of the browser navigation loop is recorded with its agent
 * decision, actions, and screenshot GCS paths. This data enables debugging
 * sessions by replaying the iteration timeline with screenshots.
 */
interface IAgenticNavIterationRepository {

    /** Saves an iteration and its screenshot records. Returns the entity with its DB-generated id. */
    suspend fun save(iteration: AgenticNavIteration): AgenticNavIteration

    /** Adds a screenshot record to an existing iteration (used for region crops discovered after initial save). */
    suspend fun addScreenshot(iterationId: AgenticNavIterationId, screenshot: ScreenshotRecord)

    /** Returns all iterations for a session, ordered by iteration number. */
    suspend fun findBySessionId(sessionId: SessionId): List<AgenticNavIteration>

    /** Returns iterations for a specific URL within a session, ordered by iteration number. */
    suspend fun findBySessionIdAndUrl(sessionId: SessionId, url: String): List<AgenticNavIteration>
}
