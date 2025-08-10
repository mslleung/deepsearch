package io.deepsearch.domain.models.valueobjects

@JvmInline
value class UserAge(val value: Int) {
    init {
        require(value >= 0) { "User age cannot be negative" }
        require(value <= 150) { "User age cannot exceed 150" }
    }
} 