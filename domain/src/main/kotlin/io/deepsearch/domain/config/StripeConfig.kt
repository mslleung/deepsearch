package io.deepsearch.domain.config

/**
 * Configuration for Stripe integration.
 */
data class StripeConfig(
    val secretKey: String,
    val publishableKey: String,
    val webhookSecret: String
) {
    init {
        require(secretKey.isNotBlank())
        require(publishableKey.isNotBlank())
        require(webhookSecret.isNotBlank())
    }
}