package io.deepsearch.domain.models.valueobjects

data class SearchBudget(
    val timeLimitMs: Long = 60_000,
    val maxLinks: Int = 100
) {
    init {
        require(timeLimitMs > 1000) { "Time limit must be > 1 second" }
        require(maxLinks > 1) { "Max links must be > 1" }
    }
}
