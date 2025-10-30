package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Domain entity representing cached semantic element identification results.
 * The pageHash is derived from the screenshot bytes to identify similar page layouts.
 */
@OptIn(ExperimentalTime::class)
data class WebpageSemanticElement(
    val pageHash: ByteArray,
    val elements: SemanticElements = SemanticElements(),
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    var version: Long = 0
)