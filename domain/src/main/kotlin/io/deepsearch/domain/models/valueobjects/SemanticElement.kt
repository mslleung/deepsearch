package io.deepsearch.domain.models.valueobjects

import kotlinx.serialization.Serializable

@Serializable
data class SemanticElement(
    val xpath: String,
    val type: SemanticElementType,
    val note: String
)


