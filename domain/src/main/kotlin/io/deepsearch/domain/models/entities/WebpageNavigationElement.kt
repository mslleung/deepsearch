package io.deepsearch.domain.models.entities

import io.deepsearch.domain.models.valueobjects.NavigationElementMatch

/**
 * Domain entity representing cached navigation element identification results.
 * The pageHash is derived from the screenshot bytes to identify similar page layouts.
 */
data class WebpageNavigationElement(
    val pageHash: ByteArray,
    val elements: List<NavigationElementMatch> = emptyList()
)