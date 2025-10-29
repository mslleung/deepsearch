package io.deepsearch.domain.models.valueobjects

@JvmInline
value class UserSubscriptionId(val value: Long) {
    override fun toString(): String = value.toString()
}
