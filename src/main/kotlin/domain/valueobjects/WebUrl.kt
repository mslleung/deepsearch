package io.deepsearch.domain.valueobjects

@JvmInline
value class WebUrl(val value: String) {
    init {
        require(value.isNotBlank()) { "URL cannot be blank" }
        require(value.startsWith("http://") || value.startsWith("https://")) { 
            "URL must start with http:// or https://" 
        }
    }
} 