package io.deepsearch.domain.models.valueobjects

data class SearchBudget(
    val timeLimitMs: Long = 60_000,
    val maxLinks: Int = 100,
    val maxSerpCalls: Int = 1,
    val maxConcurrentSessions: Int = 30
) {
    init {
        require(timeLimitMs > 1000) { "Time limit must be > 1 second" }
        require(maxLinks > 1) { "Max links must be > 1" }
        require(maxSerpCalls >= 0) { "Max SERP calls must be >= 0" }
        require(maxConcurrentSessions > 0) { "Max concurrent sessions must be > 0" }
    }
}
