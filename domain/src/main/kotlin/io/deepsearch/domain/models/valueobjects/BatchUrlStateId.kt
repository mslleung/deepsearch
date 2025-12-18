package io.deepsearch.domain.models.valueobjects

/**
 * Typed identifier for batch URL state records.
 */
@JvmInline
value class BatchUrlStateId(val value: Long) {
    init {
        require(value > 0) { "Batch URL state ID must be positive" }
    }
}

