package io.deepsearch.domain.models.valueobjects

data class SearchBudget(
    val timeLimitMs: Long = 60_000,
    val maxLinks: Int = 20
)
