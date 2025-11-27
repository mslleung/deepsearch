package io.deepsearch.domain.models.valueobjects

data class QuerySessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "QuerySessionId must not be blank" }
    }
}

