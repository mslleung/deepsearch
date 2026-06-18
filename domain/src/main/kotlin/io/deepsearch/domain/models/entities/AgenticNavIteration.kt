package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.AgenticNavIterationId
import io.deepsearch.domain.models.valueobjects.SessionId

/**
 * Represents one iteration of the agentic navigation loop for a given URL within a session.
 *
 * Each iteration captures:
 * - What the agent observed and decided (observation, decision, actions)
 * - Which screenshots were produced (raw, annotated, region crops)
 * - How long the iteration took
 *
 * The screenshots list is populated on read from the child table; on write,
 * the repository persists it alongside the iteration row.
 *
 * [id] is null before the entity has been persisted to the database.
 */
class AgenticNavIteration(
    val id: AgenticNavIterationId? = null,
    val sessionId: SessionId,
    val url: String,
    val iteration: Int,
    val observation: String?,
    val decision: String,
    val actionsJson: String,
    val screenshots: List<ScreenshotRecord> = emptyList(),
    val durationMs: Long?,
    val createdAtEpochMs: Long
) {
    fun withId(newId: AgenticNavIterationId) = AgenticNavIteration(
        id = newId,
        sessionId = sessionId,
        url = url,
        iteration = iteration,
        observation = observation,
        decision = decision,
        actionsJson = actionsJson,
        screenshots = screenshots,
        durationMs = durationMs,
        createdAtEpochMs = createdAtEpochMs
    )
}

enum class ScreenshotType {
    RAW,
    ANNOTATED,
    REGION_CROP
}

/**
 * A single screenshot file associated with a navigation iteration.
 *
 * For [ScreenshotType.REGION_CROP], [regionIndex] and [description] are populated.
 * For other types they are null.
 */
data class ScreenshotRecord(
    val type: ScreenshotType,
    val gcsPath: String,
    val regionIndex: Int? = null,
    val description: String? = null
)
