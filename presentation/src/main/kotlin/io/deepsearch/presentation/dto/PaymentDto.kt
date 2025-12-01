package io.deepsearch.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class PaymentConfigResponse(
    val publishableKey: String
)

@Serializable
data class CreateCheckoutSessionRequest(
    val planName: String
)

@Serializable
data class CreateCheckoutSessionResponse(
    val sessionId: String,
    val checkoutUrl: String
)

@Serializable
data class CreatePortalSessionRequest(
    val returnUrl: String
)

@Serializable
data class CreatePortalSessionResponse(
    val url: String
)

@Serializable
data class CreateSubscriptionIntentRequest(
    val planName: String
)

@Serializable
data class CreateSubscriptionIntentResponse(
    val subscriptionId: String,
    val clientSecret: String
)