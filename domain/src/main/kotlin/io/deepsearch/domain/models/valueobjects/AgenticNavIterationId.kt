package io.deepsearch.domain.models.valueobjects

@JvmInline
value class AgenticNavIterationId(val value: Long) {
    init {
        require(value > 0) { "AgenticNavIterationId must be positive" }
    }
}
