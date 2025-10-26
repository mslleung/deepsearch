package io.deepsearch.domain.models.valueobjects

data class ApiKeyId(val value: Int) {
    init {
        require(value > 0) { "ApiKeyId must be positive" }
    }
}

