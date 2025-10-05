package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

@Serializable
data class NavigationElementMatch(
    val xpath: String,
    val type: NavigationElementType,
    val note: String
)


