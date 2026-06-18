package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Child table linking screenshot GCS paths to their parent [AgenticNavIterationTable] row.
 *
 * Each row represents a single screenshot file (raw, annotated, nav-input, or region crop).
 * Region crops have a non-null [regionIndex] and [description].
 */
class AgenticNavScreenshotTable(
    private val iterationTable: AgenticNavIterationTable
) : Table("agentic_nav_screenshots") {
    val id = long("id").autoIncrement()
    val iterationId = long("iteration_id").references(iterationTable.id)
    val screenshotType = varchar("screenshot_type", 20)
    val gcsPath = text("gcs_path")
    val regionIndex = integer("region_index").nullable()
    val description = text("description").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, iterationId)
    }
}
