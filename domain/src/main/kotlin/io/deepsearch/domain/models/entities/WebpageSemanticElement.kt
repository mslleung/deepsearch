package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.SemanticElements

/**
 * Domain entity representing cached semantic element identification results.
 * The pageHash is derived from the screenshot bytes to identify similar page layouts.
 */
data class WebpageSemanticElement(
    val pageHash: ByteArray,
    val elements: SemanticElements = SemanticElements()
)