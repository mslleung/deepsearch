package io.deepsearch.domain.models.valueobjects

enum class ApiKeyType(val rateLimitPerMinute: Int, val prefix: String) {
    PLAYGROUND(1, "ds_test_"),
    REGULAR(20, "ds_live_");

    companion object {
        fun fromString(value: String): ApiKeyType {
            return when (value.lowercase()) {
                "playground" -> PLAYGROUND
                "regular" -> REGULAR
                else -> throw IllegalArgumentException("Invalid API key type: $value")
            }
        }
    }
}

