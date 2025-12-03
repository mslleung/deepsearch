package io.deepsearch.domain.models.entities

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Entity tracking file search stores per domain.
 * 
 * Each domain gets its own Gemini File Search Store. This entity maps
 * domains to their corresponding Gemini store resource names.
 */
@OptIn(ExperimentalTime::class)
data class FileSearchStore(
    val id: Long? = null,
    val domain: String,              // e.g., "docs.example.com"
    val geminiStoreName: String,     // Gemini resource name, e.g., "fileSearchStores/abc123"
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)

