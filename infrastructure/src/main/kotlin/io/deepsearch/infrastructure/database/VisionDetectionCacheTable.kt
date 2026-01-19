package io.deepsearch.infrastructure.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Database table for caching vision-based detection results.
 */
class VisionDetectionCacheTable : Table("vision_detection_cache") {
    /** SHA-256 hash of screenshot bytes + structural HTML (Base64 encoded) */
    val contentHash = varchar("content_hash", length = 128)
    
    /** Raw vision LLM response JSON with bounding boxes */
    val visionResponseJson = text("vision_response_json")
    
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val version = long("version").default(0)

    init {
        index(true, contentHash)
    }

    override val primaryKey = PrimaryKey(contentHash)
}
