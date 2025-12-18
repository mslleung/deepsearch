package io.deepsearch.domain.models.valueobjects

/**
 * Typed identifier for batch periodic index jobs.
 */
@JvmInline
value class BatchJobId(val value: Long) {
    init {
        require(value > 0) { "Batch job ID must be positive" }
    }
}

