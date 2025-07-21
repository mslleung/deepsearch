package io.deepsearch.domain.valueobjects

@JvmInline
value class UserName(val value: String) {
    init {
        require(value.isNotBlank()) { "User name cannot be blank" }
        require(value.length <= 50) { "User name cannot exceed 50 characters" }
        require(value.all { it.isLetterOrDigit() || it.isWhitespace() }) { 
            "User name can only contain letters, digits, and spaces" 
        }
    }
} 