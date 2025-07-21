package io.deepsearch.domain.valueobjects

@JvmInline
value class UserId(val value: Int) {
    init {
        require(value > 0) { "User ID must be positive" }
    }
} 