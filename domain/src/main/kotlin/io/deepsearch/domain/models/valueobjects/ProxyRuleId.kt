package io.deepsearch.domain.models.valueobjects

@JvmInline
value class ProxyRuleId(val value: Long) {
    init {
        require(value > 0) { "Proxy Rule ID must be positive" }
    }
}
