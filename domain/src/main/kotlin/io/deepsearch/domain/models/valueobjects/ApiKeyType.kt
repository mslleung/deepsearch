package io.deepsearch.domain.models.valueobjects

enum class ApiKeyType(val rateLimitPerMinute: Int, val prefix: String) {
    PLAYGROUND(2, "ds_test_"),
    REGULAR(20, "ds_live_"),
    BENCHMARK(1000, "ds_bench_");

    companion object {
        fun fromString(value: String): ApiKeyType {
            return when (value.lowercase()) {
                "playground" -> PLAYGROUND
                "regular" -> REGULAR
                "benchmark" -> BENCHMARK
                else -> throw IllegalArgumentException("Invalid API key type: $value")
            }
        }
    }
}

