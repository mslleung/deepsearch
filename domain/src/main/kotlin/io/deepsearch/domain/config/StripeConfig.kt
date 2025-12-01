package io.deepsearch.domain.config

/**
 * Configuration for Stripe integration.
 *
 * @property secretKey The Stripe secret API key
 * @property publishableKey The Stripe publishable key for frontend usage
 * @property webhookSecret The webhook signing secret for verifying Stripe events
 * @property frontendUrl The frontend application URL for redirect URLs after payment
 */
data class StripeConfig(
    val secretKey: String,
    val publishableKey: String,
    val webhookSecret: String,
    val frontendUrl: String
) {
    init {
        require(secretKey.isNotBlank()) { "Stripe secret key must not be blank" }
        require(publishableKey.isNotBlank()) { "Stripe publishable key must not be blank" }
        require(webhookSecret.isNotBlank()) { "Stripe webhook secret must not be blank" }
        require(frontendUrl.isNotBlank()) { "Frontend URL must not be blank" }
    }
}