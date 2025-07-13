package io.deepsearch.domain.valueobjects

@JvmInline
value class SearchQuery(val value: String) {
    init {
        require(value.isNotBlank()) { "Search query cannot be blank" }
        require(value.length <= 1000) { "Search query cannot exceed 1000 characters" }
    }
} 